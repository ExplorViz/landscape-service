package net.explorviz.landscape.api.v2.model.metrics;

import java.util.Objects;
import net.explorviz.landscape.ogm.Function;

@SuppressWarnings({"checkstyle:recordComponentName"})
public record MethodMetricCodeDto(
    String loc, String nestedBlockDepth, String cyclomatic_complexity) {
  private static final String FALLBACK = "UNKNOWN";

  public MethodMetricCodeDto(final Function ogmFunction) {
    this(
        Objects.toString(ogmFunction.getMetrics().get("loc"), FALLBACK),
        Objects.toString(ogmFunction.getMetrics().get("nestedBlockDepth"), FALLBACK),
        Objects.toString(ogmFunction.getMetrics().get("cyclomatic_complexity"), FALLBACK));
  }
}
