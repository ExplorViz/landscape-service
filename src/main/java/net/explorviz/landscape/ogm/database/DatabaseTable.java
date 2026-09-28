package net.explorviz.landscape.ogm.database;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents a table within a database. */
@NodeEntity
public class DatabaseTable {

  @Id @GeneratedValue private Long id;

  private String name;

  public DatabaseTable() {
    // Empty constructor required by Neo4j OGM
  }

  public DatabaseTable(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
