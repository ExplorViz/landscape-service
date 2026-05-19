package net.explorviz.persistence.ogm;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a unit of software that can be independently executed. The Application node has the
 * following responsibilities:
 *
 * <ul>
 *   <li><b>Runtime analysis:</b> Associates the file tree reconstructed from runtime analysis spans
 *       with a specific application name.
 *   <li><b>Runtime-Evolution Bridge:</b> Points to the directory within a repository relative to
 *       which all function fully-qualified names from runtime analysis are given. File paths from
 *       runtime data are mostly based on fqns that do not contain the entire path within the
 *       corresponding git repository from evolution analysis.
 *   <li><b>Monorepos:</b> Distinguishes between distinct projects in the case of repositories
 *       containing multiple projects (monorepos).
 * </ul>
 */
@NodeEntity
public class Application {

  /**
   * From runtime data alone, the name to give to the root directory of the application is not
   * usually known, as paths are usually given relative to the application root. This information
   * may become known at a later point when repository analysis data for the same application
   * arrives. If runtime data arrives first, we use this placeholder name for the root directory.
   */
  public static final String ROOT_NAME_PLACEHOLDER_RUNTIME = "*";

  @Id @GeneratedValue private Long id;

  private String name;

  @Relationship(type = "HAS_ROOT", direction = Relationship.Direction.OUTGOING)
  private Directory rootDirectory;

  public Application() {
    // Empty constructor required by Neo4j OGM
  }

  public Application(final String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Directory getRootDirectory() {
    return rootDirectory;
  }

  public void setRootDirectory(final Directory rootDirectory) {
    this.rootDirectory = rootDirectory;
  }
}
