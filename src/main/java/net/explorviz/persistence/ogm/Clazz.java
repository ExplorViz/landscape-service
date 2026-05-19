package net.explorviz.persistence.ogm;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import net.explorviz.persistence.proto.ClassType;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Properties;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a class in object-oriented programming languages. */
@NodeEntity
@SuppressWarnings("PMD.TooManyMethods")
public class Clazz implements Comparable<Clazz> {

  @Id @GeneratedValue private Long id;

  private String name;

  private ClassType type;

  @Relationship(type = "INHERITS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Clazz> superclasses = new TreeSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Clazz> innerClasses = new TreeSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Function> functions = new TreeSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Field> fields = new HashSet<>();

  private final Set<String> modifiers = new HashSet<>();

  private final Set<String> implementedInterfaces = new HashSet<>();

  private final Set<String> annotations = new HashSet<>();

  private final Set<String> enumValues = new HashSet<>();

  @Properties private final Map<String, Double> metrics = new HashMap<>();

  public Clazz() {
    // Empty constructor required by Neo4j OGM
  }

  public Clazz(final String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public ClassType getType() {
    return type;
  }

  public void setType(final ClassType type) {
    this.type = type;
  }

  public SortedSet<Clazz> getSuperclasses() {
    return new TreeSet<>(superclasses);
  }

  public void addSuperclass(final Clazz superclass) {
    superclasses.add(superclass);
  }

  public SortedSet<Clazz> getInnerClasses() {
    return new TreeSet<>(innerClasses);
  }

  public void addInnerClass(final Clazz innerClass) {
    innerClasses.add(innerClass);
  }

  public SortedSet<Function> getFunctions() {
    return new TreeSet<>(functions);
  }

  public void addFunction(final Function function) {
    functions.add(function);
  }

  public Set<Field> getFields() {
    return Set.copyOf(fields);
  }

  public void addField(final Field field) {
    fields.add(field);
  }

  public Set<String> getModifiers() {
    return Set.copyOf(modifiers);
  }

  public void setModifiers(final Collection<String> modifiers) {
    this.modifiers.clear();
    this.modifiers.addAll(modifiers);
  }

  public Set<String> getImplementedInterfaces() {
    return Set.copyOf(implementedInterfaces);
  }

  public void setImplementedInterfaces(final Collection<String> implementedInterfaces) {
    this.implementedInterfaces.clear();
    this.implementedInterfaces.addAll(implementedInterfaces);
  }

  public Set<String> getAnnotations() {
    return Set.copyOf(annotations);
  }

  public void setAnnotations(final Collection<String> annotations) {
    this.annotations.clear();
    this.annotations.addAll(annotations);
  }

  public Set<String> getEnumValues() {
    return Set.copyOf(enumValues);
  }

  public void setEnumValues(final Collection<String> enumValues) {
    this.enumValues.clear();
    this.enumValues.addAll(enumValues);
  }

  public Map<String, Double> getMetrics() {
    return Map.copyOf(metrics);
  }

  public void setMetrics(final Map<String, Double> metrics) {
    this.metrics.clear();
    this.metrics.putAll(metrics);
  }

  @Override
  public int compareTo(final Clazz other) {
    return name.compareTo(other.name);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Clazz otherClass)) {
      return false;
    }

    return id != null && id.equals(otherClass.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
