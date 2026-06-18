package net.explorviz.landscape.api.v2.model.metrics;

import java.util.Objects;
import net.explorviz.landscape.ogm.Clazz;

@SuppressWarnings({"checkstyle:abbreviationAsWordInName", "checkstyle:recordComponentName"})
public record ClassMetricCodeDto(
    String lineCount,
    String LCOM4,
    String cyclomatic_complexity_weighted,
    String cyclomatic_complexity) {
  private static final String FALLBACK = "UNKNOWN";

  public ClassMetricCodeDto(final Clazz ogmClazz) {
    this(
        Objects.toString(ogmClazz.getMetrics().get("lineCount"), FALLBACK),
        Objects.toString(ogmClazz.getMetrics().get("LCOM4"), FALLBACK),
        Objects.toString(ogmClazz.getMetrics().get("cyclomatic_complexity_weighted"), FALLBACK),
        Objects.toString(ogmClazz.getMetrics().get("cyclomatic_complexity"), FALLBACK));
  }
}
