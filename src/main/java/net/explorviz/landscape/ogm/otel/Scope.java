package net.explorviz.landscape.ogm.otel;

import net.explorviz.landscape.ogm.Directory;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents an OpenTelemetry instrumentation scope, which subdivide telemetry-generating services
 * into logical units (e.g. modules, libraries, ...).
 *
 * @see <a href="https://opentelemetry.io/docs/concepts/instrumentation-scope/">OpenTelemetry
 *     Documentation</a>
 */
@NodeEntity
public class Scope {
  @Id @GeneratedValue private Long id;

  private String name;

  private String version;

  /**
   * Identifier for looking up telemetry data related to this scope (e.g. for communication). Note
   * this is specifically for generic service telemetry data which could not be further narrowed
   * down to an entity contained within this scope.
   */
  private String telemetryKey;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private Directory directory;

  public Scope() {
    // Empty constructor required by Neo4j OGM
  }

  public Scope(final String name) {
    this.name = name;
    this.version = null;
  }

  public Scope(final String name, final String version) {
    this.name = name;
    this.version = version;
  }

  public String getName() {
    return name;
  }

  public String getVersion() {
    return version;
  }

  public String getTelemetryKey() {
    return telemetryKey;
  }

  public Directory getDirectory() {
    return directory;
  }
}
