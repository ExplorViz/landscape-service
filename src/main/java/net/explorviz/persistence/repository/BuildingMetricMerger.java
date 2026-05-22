package net.explorviz.persistence.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.explorviz.persistence.api.v3.model.CommitComparison;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto;

final class BuildingMetricMerger {

  private BuildingMetricMerger() {}

  static Map<String, MetricValue> mergeForComparison(
      final BuildingDto current, final BuildingDto other, final CommitComparison comp) {
    if (current != null && other != null) {
      return mergeMetrics(current.metrics(), other.metrics());
    }
    if (comp == CommitComparison.REMOVED && current != null) {
      final Map<String, MetricValue> metrics = new HashMap<>();
      current.metrics().forEach((k, v) -> metrics.put(k, new MetricValue(null, v.current())));
      return metrics;
    }
    return current != null ? current.metrics() : other.metrics();
  }

  private static Map<String, MetricValue> mergeMetrics(
      final Map<String, MetricValue> current, final Map<String, MetricValue> previous) {
    final Map<String, MetricValue> merged = new HashMap<>();
    final Set<String> allKeys = new HashSet<>(current.keySet());
    allKeys.addAll(previous.keySet());
    for (final String k : allKeys) {
      final MetricValue curr = current.get(k);
      final MetricValue prev = previous.get(k);
      merged.put(
          k,
          new MetricValue(
              curr != null ? curr.current() : null, prev != null ? prev.current() : null));
    }
    return merged;
  }
}
