package net.explorviz.persistence.ogm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.explorviz.persistence.proto.FunctionData;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Properties;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a source code function / method. */
@NodeEntity
public class Function implements Comparable<Function> {

  @Id @GeneratedValue private Long id;

  private String name;

  private String returnType;

  private boolean constructor;

  private final Set<String> annotations = new HashSet<>();

  private final Set<String> modifiers = new HashSet<>();

  private final Set<String> outgoingMethodCalls = new HashSet<>();

  private int startLine;

  private int endLine;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Parameter> parameters = new HashSet<>();

  @Properties private final Map<String, Double> metrics = new HashMap<>();

  public Function() {
    // Empty constructor required by Neo4j OGM
  }

  public Function(final String name) {
    this.name = name;
  }

  public Function(final FunctionData functionData) {
    name = functionData.getName();
    returnType = functionData.getReturnType();
    constructor = functionData.getIsConstructor();
    annotations.addAll(functionData.getAnnotationsList());
    modifiers.addAll(functionData.getModifiersList());
    outgoingMethodCalls.addAll(functionData.getOutgoingMethodCallsList());
    metrics.putAll(functionData.getMetricsMap());
    startLine = functionData.getStartLine();
    endLine = functionData.getEndLine();
    functionData.getParametersList().stream().map(Parameter::new).forEach(parameters::add);
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getReturnType() {
    return returnType;
  }

  public boolean isConstructor() {
    return constructor;
  }

  public Set<String> getAnnotations() {
    return Set.copyOf(annotations);
  }

  public Set<String> getModifiers() {
    return Set.copyOf(modifiers);
  }

  public Set<String> getOutgoingMethodCalls() {
    return Set.copyOf(outgoingMethodCalls);
  }

  public int getStartLine() {
    return startLine;
  }

  public int getEndLine() {
    return endLine;
  }

  public Set<Parameter> getParameters() {
    return Set.copyOf(parameters);
  }

  public Map<String, Double> getMetrics() {
    return Map.copyOf(metrics);
  }

  @Override
  public int compareTo(final Function other) {
    return name.compareTo(other.name);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Function otherFunction)) {
      return false;
    }

    return id != null && id.equals(otherFunction.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
