package net.explorviz.landscape.api.v3.model.landscape;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;

@RegisterForReflection
public record LanguageCountDto(String language, long files) {
  public LanguageCountDto {
    Objects.requireNonNull(language);
  }
}
