package net.explorviz.landscape.api.v2.model;

import net.explorviz.landscape.ogm.Span;

/**
 * Represents a span as used for the dynamic data in the frontend.
 *
 * @param traceId ID of the parent trace as specified by OpenTelemetry
 * @param spanId ID of the span as specified by OpenTelemetry
 * @param parentSpanId Span ID of the parent span. Put empty string for no parent
 * @param startTime Start time of the span, as Unix epoch timestamp
 * @param endTime End time of the span, as Unix epoch timestamp
 * @param methodHash This is a value we used to calculate to match spans with the functions they
 *     belong to, since multiple spans can refer to the same function. In the landscape-service, we
 *     no longer need a hash calculation as we can simply return the ID of the function node
 */
public record SpanDto(
    String traceId,
    String spanId,
    String parentSpanId,
    long startTime,
    long endTime,
    String methodHash) {

  public SpanDto(final Span ogmSpan, final String traceId) {
    this(
        traceId,
        ogmSpan.getSpanId(),
        ogmSpan.getParentSpan() != null ? ogmSpan.getParentSpan().getSpanId() : "",
        ogmSpan.getStartTime(),
        ogmSpan.getEndTime(),
        ogmSpan.getFunction().getId().toString());
  }
}
