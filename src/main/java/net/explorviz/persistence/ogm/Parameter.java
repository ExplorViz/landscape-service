package net.explorviz.persistence.ogm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.explorviz.persistence.proto.ParameterData;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents a function parameter. */
@NodeEntity
public class Parameter {

  @Id @GeneratedValue private Long id;

  private String name;

  private String type;

  private final List<String> modifiers = new ArrayList<>();

  public Parameter() {
    // Empty constructor required by Neo4j OGM
  }

  public Parameter(final String name, final String type, final Collection<String> modifiers) {
    this.name = name;
    this.type = type;
    this.modifiers.addAll(modifiers);
  }

  public Parameter(final ParameterData parameterData) {
    name = parameterData.getName();
    type = parameterData.getType();
    modifiers.addAll(parameterData.getModifiersList());
  }

  public String getName() {
    return name;
  }

  public String getType() {
    return type;
  }

  public List<String> getModifiers() {
    return List.copyOf(modifiers);
  }
}
