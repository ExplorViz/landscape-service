package net.explorviz.landscape.api.v3.model;

import java.util.Map;

/**
 * Represents aggregated communication, e.g. between files..
 *
 * @param id Unique identifier for the communication
 * @param name Name of the communication
 * @param sourceBuildingId ID of the source building (file id)
 * @param targetBuildingId ID of the target building (file id)
 * @param isBidirectional Whether the communication is bidirectional
 * @param metrics Metrics associated with the communication, e.g., requestCount
 */
public record CommunicationDto(
    String id,
    String name,
    String sourceBuildingId,
    String targetBuildingId,
    boolean isBidirectional,
    Map<String, Number> metrics) {}
