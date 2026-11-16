package net.explorviz.landscape.util.otel;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.semconv.DbAttributes;
import java.util.Map;

/**
 * Factory for creating OpenTelemetry spans with predefined attributes and other parameters such
 * that frequently-executed operations are instrumented in a consistent manner.
 */
public final class SpanFactory {

  private SpanFactory() {}

  /**
   * Start a new span representing a Neo4j database operation. Sets certain attributes from the
   * database semantic conventions
   *
   * @param tracer The tracer instance via which to create the span.
   * @param query The Cypher query to be executed.
   * @param params The parameters passed into the Cypher query.
   * @return A new span of kind {@link SpanKind#CLIENT}, with database attributes set for the
   *     provided Neo4j query.
   */
  public static Span startNeo4jSpan(
      final Tracer tracer, final String spanName, final String query, final Map<String, ?> params) {
    final SpanBuilder builder =
        tracer
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.current().with(Span.current()))
            .setAttribute(DbAttributes.DB_SYSTEM_NAME, "neo4j")
            .setAttribute(DbAttributes.DB_QUERY_TEXT, query);

    params.forEach((k, v) -> builder.setAttribute("db.query.parameter." + k, v.toString()));

    return builder.startSpan();
  }
}
