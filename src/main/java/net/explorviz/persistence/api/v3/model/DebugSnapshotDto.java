package net.explorviz.persistence.api.v3.model;

/**
 * Represents a snapshot captured during a debug run together with basic metadata.
 *
 * @param id Unique identifier of the debug snapshot
 * @param lineOfBreakpoint Line number at which program execution was paused when the snapshot was
 *     captured
 * @param numOfVariables Number of variables captured in this snapshot
 * @param timestamp Timestamp indicating when the snapshot was captured
 */
public record DebugSnapshotDto(
    String id, Integer lineOfBreakpoint, Integer numOfVariables, Long timestamp) {}
