package net.explorviz.landscape.api.v3.model;

import java.util.List;

public record EvolutionAnimationDto(
    String repositoryName,
    List<EvolutionFrameDto> frames) {}
