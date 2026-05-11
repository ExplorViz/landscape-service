package net.explorviz.persistence.ogm;

import java.util.SortedSet;
import java.util.TreeSet;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a snapshot of the program state captured during a debug run.
 *
 * <p>A new instance is created whenever a snapshot is triggered from the VS Code extension.
 */
@NodeEntity
public class DebugSnapshot implements Comparable<DebugSnapshot> {

  @Id @GeneratedValue private Long id;

  private int lineOfBreakpoint;

  private int numOfVariables;

  private long timestamp;

  @Relationship(type = "CAPTURES", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Variable> variables = new TreeSet<>();

  @Relationship(type = "HAS_BREAKPOINT_IN", direction = Relationship.Direction.OUTGOING)
  private FileRevision fileRevision;

  public DebugSnapshot() {
    // Empty constructor required by Neo4j OGM
  }

  public DebugSnapshot(final int timestamp, final int lineOfBreakpoint) {
    this.timestamp = timestamp;
    this.lineOfBreakpoint = lineOfBreakpoint;
  }

  public Long getId() {
    return id;
  }

  public int getLineOfBreakpoint() {
    return lineOfBreakpoint;
  }

  public void setLineOfBreakpoint(final int lineOfBreakpoint) {
    this.lineOfBreakpoint = lineOfBreakpoint;
  }

  public int getNumOfVariables() {
    return numOfVariables;
  }

  public void setNumOfVariables(final int numOfVariables) {
    this.numOfVariables = numOfVariables;
  }

  public SortedSet<Variable> getVariables() {
    return new TreeSet<>(variables);
  }

  public void addVariable(final Variable variable) {
    variables.add(variable);
  }

  public FileRevision getFileRevision() {
    return fileRevision;
  }

  public void setFileRevision(final FileRevision fileRevision) {
    this.fileRevision = fileRevision;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(final long timestamp) {
    this.timestamp = timestamp;
  }

  @Override
  public int compareTo(final DebugSnapshot other) {

    final int timestampCompare = Long.compare(timestamp, other.timestamp);

    if (timestampCompare != 0) {
      return timestampCompare;
    }

    return Long.compare(id, other.id);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final DebugSnapshot otherSnapshot)) {
      return false;
    }

    return id != null && id.equals(otherSnapshot.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
