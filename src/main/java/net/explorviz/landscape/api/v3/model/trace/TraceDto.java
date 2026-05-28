package net.explorviz.landscape.api.v3.model.trace;

import java.util.List;
import net.explorviz.landscape.ogm.Trace;

/**
 * Represents a trace as used for the dynamic data in the frontend.
 *
 * @param landscapeToken String identifier of the visualization landscape
 * @param traceId ID of the trace as specified by OpenTelemetry
 * @param startTime Start time of the trace, as Unix epoch timestamp
 * @param endTime End time of the trace, as Unix epoch timestamp
 * @param spanList Spans that are part of this trace, see {@link SpanDto}
 */
public record TraceDto(
    String landscapeToken, String traceId, long startTime, long endTime, List<SpanDto> spanList) {

  public TraceDto(final Trace ogmTrace, final String landscapeToken) {
    this(
        landscapeToken,
        ogmTrace.getTraceId(),
        ogmTrace.getStartTime(),
        ogmTrace.getEndTime(),
        ogmTrace.getSpans().stream().map(s -> new SpanDto(s, ogmTrace.getTraceId())).toList());
  }
}
