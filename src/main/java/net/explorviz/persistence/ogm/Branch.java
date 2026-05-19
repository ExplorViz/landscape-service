package net.explorviz.persistence.ogm;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents a git branch. */
@NodeEntity
public class Branch {

  @Id @GeneratedValue private Long id;

  private String name;

  public Branch() {
    // Empty constructor required by Neo4j OGM
  }

  public Branch(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
