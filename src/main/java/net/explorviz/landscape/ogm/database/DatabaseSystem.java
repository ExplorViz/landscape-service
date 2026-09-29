package net.explorviz.landscape.ogm.database;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a database management system (DBMS). */
@NodeEntity
public class DatabaseSystem {

  @Id @GeneratedValue private Long id;

  private String name;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Database> databases = new HashSet<>();

  /** Contains those tables of this DBMS for which the database could not be identified. */
  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<DatabaseTable> tables = new HashSet<>();

  public DatabaseSystem() {
    // Empty constructor required by Neo4j OGM
  }

  public DatabaseSystem(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public Set<Database> getDatabases() {
    return new HashSet<>(databases);
  }

  public Set<DatabaseTable> getTables() {
    return new HashSet<>(tables);
  }
}
