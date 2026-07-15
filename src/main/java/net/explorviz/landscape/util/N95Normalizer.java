package net.explorviz.landscape.util;

import java.util.Arrays;

public class N95Normalizer {

  private final double p95;
  private final double max;

  public N95Normalizer(final double[] rawValues) {
    final double[] sorted = rawValues.clone();
    Arrays.sort(sorted);
    this.max = sorted.length == 0 ? 0.0 : sorted[sorted.length - 1];
    this.p95 = perc95(sorted);
  }

  public double normalize(final double rawValue) {
    if (p95 > 0) {
      return Math.min(1, rawValue / p95);
    }
    if (p95 == 0 && max > 0) {
      return rawValue / max;
    }
    return 0.0;
  }

  private double perc95(final double[] values) {
    return values.length == 0 ? 0.0 : values[((int) Math.ceil(0.95 * values.length)) - 1];
  }
}
