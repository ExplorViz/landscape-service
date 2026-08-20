package net.explorviz.landscape.api.v3.model.landscape;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public record AnimationWindowDeltaDto(
    int totalCount, int windowStart, List<AnimationFrameDeltaDto> frames) {}
