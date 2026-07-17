package net.explorviz.landscape.api.v3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;

@RegisterForReflection
public record SocialMetricDto(
    Long fileRevisionId, String filePath, Map<String, MetricScore> metrics) {

  @RegisterForReflection
  public record MetricScore(double raw, double normalized) {}
}
