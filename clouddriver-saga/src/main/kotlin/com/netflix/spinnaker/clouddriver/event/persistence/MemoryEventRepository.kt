/*
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.clouddriver.event.persistence

import com.netflix.spectator.api.Registry
import com.netflix.spinnaker.clouddriver.event.Aggregate
import com.netflix.spinnaker.clouddriver.event.EventMetadata
import com.netflix.spinnaker.clouddriver.event.EventPublisher
import com.netflix.spinnaker.clouddriver.event.SpinEvent
import com.netflix.spinnaker.clouddriver.event.config.MemoryEventRepositoryConfigProperties
import com.netflix.spinnaker.clouddriver.event.exceptions.AggregateChangeRejectedException
import com.netflix.spinnaker.kork.exceptions.SystemException
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.time.Instant

/**
 * An in-memory only [EventRepository]. This implementation should only be used for testing.
 */
class MemoryEventRepository(
  private val config: MemoryEventRepositoryConfigProperties,
  private val eventPublisher: EventPublisher,
  private val registry: Registry
) : EventRepository {

  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private val aggregateCountId = registry.createId("eventing.aggregates")
  private val aggregateWriteCountId = registry.createId("eventing.aggregates.writes")
  private val aggregateReadCountId = registry.createId("eventing.aggregates.reads")
  private val eventCountId = registry.createId("eventing.events")
  private val eventWriteCountId = registry.createId("eventing.events.writes")
  private val eventReadCountId = registry.createId("eventing.events.reads")

  private val events: MutableMap<Aggregate, MutableList<SpinEvent>> = mutableMapOf()

  override fun save(aggregateType: String, aggregateId: String, originatingVersion: Long, events: List<SpinEvent>) {
    registry.counter(aggregateWriteCountId).increment()

    val aggregate = getAggregate(aggregateType, aggregateId, originatingVersion)

    if (aggregate.version != originatingVersion) {
      // If this is being thrown, ensure that the originating process is retried on the latest aggregate version
      // by re-reading the events list.
      throw AggregateChangeRejectedException(
        "Attempting to save events against an old aggregate version " +
        "(version: ${aggregate.version}, originatingVersion: $originatingVersion)")
    }

    events.forEachIndexed { index, sagaEvent ->
      // TODO(rz): Plugin more metadata (provenance, serviceVersion, etc)
      sagaEvent.metadata = EventMetadata(
        aggregate.version + (index + 1)
      )
    }

    registry.counter(eventWriteCountId).increment(events.size.toLong())
    this.events[aggregate]!!.addAll(events)
    aggregate.incrementVersion()

    events.forEach { eventPublisher.publish(it) }

    cleanup()
  }

  override fun list(aggregateType: String, aggregateId: String): List<SpinEvent> {
    registry.counter(eventReadCountId).increment()

    return getAggregate(aggregateType, aggregateId)
      .let {
        events[it]?.toList()
      }
      ?: throw MissingAggregateEventsException(aggregateType, aggregateId)
  }

  private fun getAggregate(aggregateType: String, aggregateId: String, originatingVersion: Long? = null): Aggregate {
    registry.counter(aggregateReadCountId).increment()

    val agg = events.keys.find { it.type == aggregateType && it.id == aggregateId }
    if (agg != null) {
      return agg
    }

    if (originatingVersion != null && originatingVersion > 0) {
      // Just don't pass an originating version if you know the aggregate is new.
      throw AggregateChangeRejectedException("An originating version greater than 0 was provided for a new aggregate")
    }

    val aggregate = Aggregate(
      aggregateType,
      aggregateId,
      0L
    )

    this.events[aggregate] = mutableListOf()

    return aggregate
  }

  @Scheduled(fixedDelayString = "spinnaker.clouddriver.eventing.memory-repository.cleanup-job-delay-ms")
  private fun cleanup() {
    registry.counter(eventReadCountId).increment()

    config.maxAggregateAgeMs
      ?.let { Duration.ofMillis(it) }
      ?.let { maxAge ->
        val horizon = Instant.now().minus(maxAge)
        log.info("Cleaning up aggregates last updated earlier than $maxAge ($horizon)")
        events.entries
          .filter { it.value.any { event -> event.metadata.timestamp.isBefore(horizon) } }
          .map { it.key }
          .forEach {
            log.trace("Cleaning up $it")
            events.remove(it)
          }
      }

    config.maxAggregatesCount
      ?.let { maxCount ->
        log.info("Cleaning up aggregates to max $maxCount items, pruning by earliest updated")
        events.entries
          // Flatten into pairs of List<Aggregate, SpinEvent>
          .flatMap { entry ->
            entry.value.map { Pair(entry.key, it) }
          }
          .sortedBy { it.second.metadata.timestamp }
          .subList(0, Math.max(events.size - maxCount, 0))
          .forEach {
            log.trace("Cleaning up ${it.first}")
            events.remove(it.first)
          }
      }
  }

  @Scheduled(fixedRate = 1_000)
  private fun recordMetrics() {
    registry.gauge(aggregateCountId).set(events.size.toDouble())
    registry.gauge(eventCountId).set(events.flatMap { it.value }.size.toDouble())
  }

  inner class MissingAggregateEventsException(aggregateType: String, aggregateId: String) : SystemException(
    "Aggregate $aggregateType/$aggregateId is missing its internal events list store"
  )
}
