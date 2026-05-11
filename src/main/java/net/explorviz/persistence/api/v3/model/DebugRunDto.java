package net.explorviz.persistence.api.v3.model;

/**
 * Represents a debugging session together with basic metadata.
 *
 * @param id Unique identifier of the debug run
 * @param status Current status of the debug run, for example "finished", "paused", or "running"
 * @param numOfSnapshots Number of snapshots captured for this debug run
 * @param startTime Start time of the debug run
 * @param endTime End time of the debug run, if the run has finished
 */
public record DebugRunDto(
    String id, String status, Integer numOfSnapshots, Long startTime, Long endTime) {}
