package net.explorviz.landscape.api.v3.model.trace;

import net.explorviz.landscape.repository.TraceRepository.Timestamp;

/**
 * Represents a timestamp with span count information. A timestamp represents the starting point of
 * a time range within which some number of spans started.
 *
 * @param epochNano Timestamp in nanoseconds since Unix epoch
 * @param spanCount Number of spans at this timestamp
 */
public record TimestampDto(long epochNano, long spanCount) {

  public TimestampDto(final Timestamp timestamp) {
    this(timestamp.startTimeEpochNano(), timestamp.spanCount());
  }
}
