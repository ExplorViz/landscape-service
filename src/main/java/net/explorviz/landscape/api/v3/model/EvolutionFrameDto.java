package net.explorviz.landscape.api.v3.model;

import java.util.Map;

public record EvolutionFrameDto(
    String commitHash,
    long timestamp,
    String branch,
    Map<String, String> buildingStates) {}
