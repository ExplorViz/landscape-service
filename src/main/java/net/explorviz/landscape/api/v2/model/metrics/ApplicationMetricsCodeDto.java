package net.explorviz.landscape.api.v2.model.metrics;

import java.util.Map;

/**
 * Container class for code metrics concerning a particular application's content.
 *
 * @param fileMetrics Maps file path -> file metrics object
 * @param classMetrics Maps fully qualified class name -> class metrics object
 * @param methodMetrics Maps fully qualified method name -> method metrics object
 */
public record ApplicationMetricsCodeDto(
    Map<String, FileMetricCodeDto> fileMetrics,
    Map<String, ClassMetricCodeDto> classMetrics,
    Map<String, MethodMetricCodeDto> methodMetrics) {}
