package net.explorviz.landscape.util;

import java.util.Arrays;

public class MetricNormalizer {

  private final boolean logScale;
  private final double anchorValue;

  public record NormalizationOptions(boolean logScale, double quantile) {
    public static final NormalizationOptions DEFAULT = new NormalizationOptions(true, 0.99);
    public static final NormalizationOptions LEGACY = new NormalizationOptions(false, 0.95);
  }

  public MetricNormalizer(final double[] rawValues) {
    this(rawValues, NormalizationOptions.LEGACY);
  }

  public MetricNormalizer(final double[] rawValues, final NormalizationOptions opts) {
    this.logScale = opts.logScale;
    // filter out 0 values, apply log if set, sort values for selector
    final double[] active =
        Arrays.stream(rawValues)
            .filter(value -> value > 0)
            .map(value -> opts.logScale ? Math.log1p(value) : value)
            .sorted()
            .toArray();

    this.anchorValue =
        active.length == 0 ? 0.0 : active[((int) Math.ceil(opts.quantile() * active.length)) - 1];
  }

  public double normalize(final double rawValue) {
    if (anchorValue <= 0) {
      return 0.0;
    }
    final double value = logScale ? Math.log1p(rawValue) : rawValue;
    return Math.max(0.0, Math.min(1.0, value / anchorValue));
  }
}
