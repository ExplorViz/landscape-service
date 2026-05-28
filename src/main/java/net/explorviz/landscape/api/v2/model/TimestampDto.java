package net.explorviz.landscape.api.v2.model;

import net.explorviz.landscape.repository.TraceRepository.Timestamp;

/**
 * Represents a timestamp with span count information, as used by v2-API.
 *
 * @param epochNano Timestamp in nanoseconds since Unix epoch
 * @param spanCount Number of spans at this timestamp
 */
public record TimestampDto(Number epochNano, Number spanCount) {

  public TimestampDto(final Timestamp timestamp) {
    this(timestamp.startTimeEpochNano(), timestamp.spanCount());
  }
}
