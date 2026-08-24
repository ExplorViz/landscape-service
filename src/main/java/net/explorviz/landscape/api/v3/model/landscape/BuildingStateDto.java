package net.explorviz.landscape.api.v3.model.landscape;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

@RegisterForReflection
public record BuildingStateDto(
    String fqn, int lastChangeOrdinal, long lastChangeDate, String lastAction) {
  public BuildingStateDto {
    Objects.requireNonNull(fqn);
    Objects.requireNonNull(lastAction);
  }
}
