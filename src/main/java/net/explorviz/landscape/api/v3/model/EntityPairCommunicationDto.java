package net.explorviz.landscape.api.v3.model;

import java.util.List;

/**
 * Represents communication between a specific pair of entities (files or directories).
 *
 * @param sourceEntityId ID of the source entity
 * @param sourceEntityName Name of the source entity
 * @param targetEntityId ID of the target entity
 * @param targetEntityName Name of the target entity
 * @param isBidirectional Whether communication occurs in both directions
 * @param functions List of function calls between these entities
 */
public record EntityPairCommunicationDto(
    Long sourceEntityId,
    String sourceEntityName,
    Long targetEntityId,
    String targetEntityName,
    boolean isBidirectional,
    List<SimpleFunctionDto> functions) {}
