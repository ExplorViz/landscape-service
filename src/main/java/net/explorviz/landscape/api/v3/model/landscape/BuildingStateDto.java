package net.explorviz.landscape.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildingStateDto(
    String fqn, int lastChangeOrdinal, long lastChangeDate, String lastAction, Double metric) {
  public BuildingStateDto {
    Objects.requireNonNull(fqn);
    Objects.requireNonNull(lastAction);
  }
}
