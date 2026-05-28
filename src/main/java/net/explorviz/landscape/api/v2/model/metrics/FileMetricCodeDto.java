package net.explorviz.landscape.api.v2.model.metrics;

import java.util.Objects;
import net.explorviz.landscape.ogm.FileRevision;

@SuppressWarnings({"checkstyle:recordComponentName"})
public record FileMetricCodeDto(String loc, String cyclomatic_complexity) {
  private static final String FALLBACK = "UNKNOWN";

  public FileMetricCodeDto(final FileRevision ogmFile) {
    this(
        Objects.toString(ogmFile.getMetrics().get("loc"), FALLBACK),
        Objects.toString(ogmFile.getMetrics().get("cyclomatic_complexity"), FALLBACK));
  }
}
