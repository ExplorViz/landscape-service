package net.explorviz.persistence.ogm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.explorviz.persistence.proto.FieldData;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents a field / member variable of a class. */
@NodeEntity
public class Field {

  @Id @GeneratedValue private Long id;

  private String name;

  private String type;

  private final List<String> modifiers = new ArrayList<>();

  public Field() {
    // Empty constructor required by Neo4j OGM
  }

  public Field(final String name, final String type, final Collection<String> modifiers) {
    this.name = name;
    this.type = type;
    this.modifiers.addAll(modifiers);
  }

  public Field(final FieldData fieldData) {
    name = fieldData.getName();
    type = fieldData.getType();
    modifiers.addAll(fieldData.getModifiersList());
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
