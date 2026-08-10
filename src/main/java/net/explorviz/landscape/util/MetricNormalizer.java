package net.explorviz.landscape.util;

import java.util.Arrays;

/** Normalizes social metric results into [0,1]. */
public class MetricNormalizer {

  private final boolean logScale;
  private final double anchorValue;

  /**
   * Defines options for the normalization.
   *
   * @param logScale whether to apply {@code log1p}
   * @param quantile the quantile to use for determining anchor value
   */
  public record NormalizationOptions(boolean logScale, double quantile) {
    /** Log scaling enabled, anchored at 99th percentile. */
    public static final NormalizationOptions DEFAULT = new NormalizationOptions(true, 0.99);
  }

  /**
   * Creates an instance of the normalizer by extracting the anchor from the values array.
   *
   * @param rawValues the raw values array
   * @param opts the normalization options record
   */
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

  /** Applies normalization to a single value. */
  public double normalize(final double rawValue) {
    if (anchorValue <= 0) {
      return 0.0;
    }
    final double value = logScale ? Math.log1p(rawValue) : rawValue;
    return Math.max(0.0, Math.min(1.0, value / anchorValue));
  }
}
