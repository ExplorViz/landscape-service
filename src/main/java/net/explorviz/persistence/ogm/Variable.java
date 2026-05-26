package net.explorviz.persistence.ogm;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Properties;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a variable and its value at a specific point during program execution.
 *
 * <p>A new instance is created for each snapshot captured while program execution is paused during
 * a debug run.
 */
@NodeEntity
public class Variable implements Comparable<Variable> {

  @Id @GeneratedValue private Long id;

  private String instanceId;

  private String name;

  private String type;

  private String value;

  @Properties private final Map<String, Double> metrics = new HashMap<>();

  @Relationship(type = "MARKED_IN", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<FileRevision> fileRevisions = new TreeSet<>();

  public Variable() {
    // Empty constructor required by Neo4j OGM
  }

  public Variable(final String name) {
    this.name = name;
  }

  public Variable(final String name, final String value) {
    this.name = name;
    this.value = value;
  }

  public Variable(final String name, final String value, final String instanceId) {
    this.name = name;
    this.value = value;
    this.instanceId = instanceId;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getValue() {
    return value;
  }

  public void setValue(final String value) {
    this.value = value;
  }

  public String getType() {
    return type;
  }

  public String getInstanceId() {
    return instanceId;
  }

  public void setType(final String type) {
    this.type = type;
  }

  public Map<String, Double> getMetrics() {
    return Map.copyOf(metrics);
  }

  public void setMetrics(final Map<String, Double> metrics) {
    this.metrics.putAll(metrics);
  }

  @Override
  public int compareTo(final Variable other) {
    return name.compareTo(other.name);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Variable otherVar)) {
      return false;
    }

    return id != null && id.equals(otherVar.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
