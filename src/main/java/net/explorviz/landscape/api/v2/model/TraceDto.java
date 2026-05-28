package net.explorviz.landscape.api.v2.model;

import java.util.List;
import net.explorviz.landscape.ogm.Trace;

/**
 * Represents a trace as used for the dynamic data in the frontend.
 *
 * @param landscapeToken String identifier of the visualization landscape
 * @param traceId ID of the trace as specified by OpenTelemetry
 * @param gitCommitChecksum Hash of the git commit associated with this trace
 * @param startTime Start time of the trace, as Unix epoch timestamp
 * @param endTime End time of the trace, as Unix epoch timestamp
 * @param duration Duration of the trace, either milliseconds or nanoseconds depending on format of
 *     start and end timestamps
 * @param overallRequestCount Included for compatibility reasons, always fixed to 1
 * @param traceCount Included for compatibility reasons, always fixed to 1
 * @param spanList Spans that are part of this trace, see {@link SpanDto}
 */
public record TraceDto(
    String landscapeToken,
    String traceId,
    String gitCommitChecksum,
    long startTime,
    long endTime,
    long duration,
    long overallRequestCount,
    long traceCount,
    List<SpanDto> spanList) {

  public TraceDto(
      final Trace ogmTrace, final String landscapeToken, final String gitCommitChecksum) {
    this(
        landscapeToken,
        ogmTrace.getTraceId(),
        gitCommitChecksum,
        ogmTrace.getStartTime(),
        ogmTrace.getEndTime(),
        ogmTrace.getEndTime() - ogmTrace.getStartTime(),
        1,
        1,
        ogmTrace.getSpans().stream().map(s -> new SpanDto(s, ogmTrace.getTraceId())).toList());
  }
}
