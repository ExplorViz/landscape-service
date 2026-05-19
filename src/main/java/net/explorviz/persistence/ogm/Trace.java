package net.explorviz.persistence.ogm;

import java.util.SortedSet;
import java.util.TreeSet;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents an OpenTelemetry Trace.
 *
 * @see <a href="https://opentelemetry.io/docs/concepts/signals/traces/">OpenTelemetry
 *     documentation</a>
 */
@NodeEntity
public class Trace {

  /**
   * Use a generated ID as opposed to OpenTelemetry's traceId since the same trace could
   * theoretically appear in multiple landscapes.
   */
  @Id @GeneratedValue private Long id;

  private String traceId;

  private Long startTime;

  private Long endTime;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Span> spans = new TreeSet<>();

  public Trace() {
    // Empty constructor required by Neo4j OGM
  }

  public Trace(final String traceId) {
    this.traceId = traceId;
  }

  public String getTraceId() {
    return traceId;
  }

  public Long getStartTime() {
    return startTime;
  }

  public void setStartTime(final long startTime) {
    this.startTime = startTime;
  }

  public Long getEndTime() {
    return endTime;
  }

  public void setEndTime(final long endTime) {
    this.endTime = endTime;
  }

  public SortedSet<Span> getSpans() {
    return new TreeSet<>(spans);
  }

  public void addSpan(final Span span) {
    spans.add(span);
    startTime = startTime != null ? Math.min(startTime, span.getStartTime()) : span.getStartTime();
    endTime = endTime != null ? Math.max(endTime, span.getEndTime()) : span.getEndTime();
  }
}
