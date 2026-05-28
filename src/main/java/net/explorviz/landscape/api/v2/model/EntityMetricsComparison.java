package net.explorviz.landscape.api.v2.model;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * Compares a metric for a specific entity across two commits.
 *
 * @param entityName Fully qualified name of the entity
 * @param metricMap Maps a metric name (e.g. "LCOM4") to a container for the old and the new value
 */
public record EntityMetricsComparison(String entityName, Map<String, ValueComparison> metricMap) {
  public record ValueComparison(@Nullable String oldValue, String newValue) {}
}
