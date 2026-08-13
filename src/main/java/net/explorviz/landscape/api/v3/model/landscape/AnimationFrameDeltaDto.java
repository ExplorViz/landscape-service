package net.explorviz.landscape.api.v3.model.landscape;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;

/** Keyframes carry the full present {@code state} plus the {@code changes} of that frame. */
@RegisterForReflection
public record AnimationFrameDeltaDto(
    String commitHash,
    long authorDate,
    int ordinal,
    boolean keyframe,
    List<BuildingStateDto> state,
    List<BuildingChangeDto> changes) {
  public AnimationFrameDeltaDto {
    Objects.requireNonNull(commitHash);
  }
}
