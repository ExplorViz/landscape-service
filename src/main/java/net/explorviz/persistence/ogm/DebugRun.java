package net.explorviz.persistence.ogm;

import java.util.SortedSet;
import java.util.TreeSet;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a debugging session for a program contained in a Git repository.
 *
 * <p>A new instance is created whenever a debugging session is started from the VS Code extension
 * and the first snapshot is captured.
 */
@NodeEntity
public class DebugRun implements Comparable<DebugRun> {


  @Id @GeneratedValue private Long id;

  private String status;

  private int numOfSnapshots;

  private int numOfVariables;

  private long startTime;

  private long endTime;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<DebugSnapshot> debugSnapshots = new TreeSet<>();

  @Relationship(type = "RUNS_ON", direction = Relationship.Direction.OUTGOING)
  private Commit commit;

  public DebugRun() {
    // Empty constructor required by Neo4j OGM
  }

  public DebugRun(final int startTime, final int numOfSnapshots) {
    this.startTime = startTime;
    this.numOfSnapshots = numOfSnapshots;
  }

  public Long getId() {
    return id;
  }

  public int getNumOfSnapshots() {
    return numOfSnapshots;
  }

  public void setNumOfSnapshots(final int numOfSnapshots) {
    this.numOfSnapshots = numOfSnapshots;
  }

  public int getNumOfVariables() {
    return numOfVariables;
  }

  public void setNumOfVariables(final int numOfVariables) {
    this.numOfVariables = numOfVariables;
  }

  public SortedSet<DebugSnapshot> getDebugSnapshots() {
    return new TreeSet<>(debugSnapshots);
  }

  public void addDebugSnapshot(final DebugSnapshot debugSnapshot) {
    debugSnapshots.add(debugSnapshot);
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(final String status) {
    this.status = status;
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

  @Override
  public int compareTo(final DebugRun other) {

    final int startCompare = Long.compare(startTime, other.startTime);

    if (startCompare != 0) {
      return startCompare;
    }

    final int endCompare = Long.compare(other.endTime, endTime);

    return endCompare == 0 ? Long.compare(other.id, id) : endCompare;
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final DebugRun otherRun)) {
      return false;
    }

    return id != null && id.equals(otherRun.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
