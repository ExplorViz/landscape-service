package net.explorviz.landscape.api.v3.model.trace;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Optional;
import net.explorviz.landscape.ogm.Span;

/**
 * Represents a span as used for the dynamic data in the frontend.
 *
 * @param traceId ID of the parent trace as specified by OpenTelemetry
 * @param spanId ID of the span as specified by OpenTelemetry
 * @param parentSpanId Span ID of the parent span. Put empty string for no parent
 * @param startTime Start time of the span, as Unix epoch timestamp
 * @param endTime End time of the span, as Unix epoch timestamp
 * @param functionId ID of the flat landscape function that this span represents
 */
public record SpanDto(
    String traceId,
    String spanId,
    @JsonInclude(Include.NON_NULL) String parentSpanId,
    long startTime,
    long endTime,
    String functionId) {

  public SpanDto(final Span ogmSpan, final String traceId) {
    this(
        traceId,
        ogmSpan.getSpanId(),
        Optional.ofNullable(ogmSpan.getParentSpan()).map(Span::getSpanId).orElse(null),
        ogmSpan.getStartTime(),
        ogmSpan.getEndTime(),
        ogmSpan.getFunction().getId().toString());
  }
}
