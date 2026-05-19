package net.explorviz.persistence.ogm;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents an OpenTelemetry Span.
 *
 * @see <a href="https://opentelemetry.io/docs/concepts/signals/traces/#spans">OpenTelemetry
 *     documentation</a>
 */
@NodeEntity
public class Span implements Comparable<Span> {

  /**
   * Use a generated ID as opposed to OpenTelemetry's spanId since the same span could theoretically
   * appear in multiple landscapes.
   */
  @Id @GeneratedValue private Long id;

  private String spanId;

  private long startTime;

  private long endTime;

  @Relationship(type = "HAS_PARENT", direction = Relationship.Direction.OUTGOING)
  private Span parentSpan;

  @Relationship(type = "REPRESENTS", direction = Relationship.Direction.OUTGOING)
  private Function function;

  public Span() {
    // Empty constructor required by Neo4j OGM
  }

  public Span(final String spanId) {
    this.spanId = spanId;
  }

  public String getSpanId() {
    return spanId;
  }

  public long getStartTime() {
    return startTime;
  }

  public void setStartTime(final long startTime) {
    this.startTime = startTime;
  }

  public long getEndTime() {
    return endTime;
  }

  public void setEndTime(final long endTime) {
    this.endTime = endTime;
  }

  public Span getParentSpan() {
    return parentSpan;
  }

  public void setParentSpan(final Span parentSpan) {
    this.parentSpan = parentSpan;
  }

  public Function getFunction() {
    return function;
  }

  public void setFunction(final Function function) {
    this.function = function;
  }

  @Override
  public int compareTo(final Span other) {

    final int startCompare = Long.compare(startTime, other.startTime);

    if (startCompare != 0) {
      return startCompare;
    }

    final int endCompare = Long.compare(other.endTime, endTime);

    return endCompare == 0 ? spanId.compareTo(other.spanId) : endCompare;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Span otherSpan)) {
      return false;
    }

    return spanId != null && spanId.equals(otherSpan.spanId);
  }

  @Override
  public int hashCode() {
    return spanId != null ? spanId.hashCode() : System.identityHashCode(this);
  }
}
