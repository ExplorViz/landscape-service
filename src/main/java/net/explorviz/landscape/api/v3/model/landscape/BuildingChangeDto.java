package net.explorviz.landscape.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BuildingChangeDto(String fqn, String action, Double metric) {
  public BuildingChangeDto {
    Objects.requireNonNull(fqn);
    Objects.requireNonNull(action);
  }

  public BuildingChangeDto(final String fqn, final String action) {
    this(fqn, action, null);
  }
}
