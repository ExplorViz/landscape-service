package net.explorviz.landscape.api.v3.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Provides the value for a particular metric. If applicable, for example in the case of a commit
 * comparison, a previous value can also be provided to compare against the current value.
 *
 * @param current The current value for this metric
 * @param previous An optional previous value of this metric to compare against
 */
public record MetricValue(String current, @JsonInclude(Include.NON_NULL) String previous) {

  public MetricValue(final String currentValue) {
    this(currentValue, null);
  }

  public static Map<String, MetricValue> fromMap(final Map<String, Double> map) {
    return map.entrySet().stream()
        .collect(Collectors.toMap(Entry::getKey, e -> new MetricValue(e.getValue().toString())));
  }

  public static Map<String, MetricValue> fromMaps(
      final Map<String, Double> currentMap, final Map<String, Double> previousMap) {
    return currentMap.entrySet().stream()
        .collect(
            Collectors.toMap(
                Entry::getKey,
                e ->
                    new MetricValue(
                        e.getValue().toString(),
                        Optional.ofNullable(previousMap.get(e.getKey()))
                            .map(Object::toString)
                            .orElse(null))));
  }
}
