package net.explorviz.persistence.ogm;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/**
 * Represents a commit tag as used by git.
 *
 * @see <a href="https://git-scm.com/book/en/v2/Git-Basics-Tagging">git Documentation</a>
 */
@NodeEntity
public class Tag {

  @Id @GeneratedValue private Long id;

  private String name;

  public Tag() {
    // Empty constructor required by Neo4j OGM
  }

  public Tag(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
