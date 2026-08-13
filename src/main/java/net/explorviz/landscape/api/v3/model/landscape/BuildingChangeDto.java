package net.explorviz.landscape.api.v3.model.landscape;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

@RegisterForReflection
public record BuildingChangeDto(String fqn, String action) {
  public BuildingChangeDto {
    Objects.requireNonNull(fqn);
    Objects.requireNonNull(action);
  }
}
