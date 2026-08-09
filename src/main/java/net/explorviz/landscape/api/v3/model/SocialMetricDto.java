package net.explorviz.landscape.api.v3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;

/**
 * Represents a collection of social metrics scores for a single FileRevision.
 *
 * @param fileRevisionId the id of the FileRevision
 * @param filePath the file path of the FileRevision
 * @param metrics map of metric names to scores
 */
@RegisterForReflection
public record SocialMetricDto(
    Long fileRevisionId, String filePath, Map<String, MetricScore> metrics) {

  /**
   * Holds the scores for a single social metric.
   *
   * @param raw the raw score
   * @param normalized the normalized score
   */
  @RegisterForReflection
  public record MetricScore(double raw, double normalized) {}
}
