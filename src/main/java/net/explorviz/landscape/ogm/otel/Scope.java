package net.explorviz.landscape.ogm.otel;

import java.util.HashSet;
import java.util.Set;
import net.explorviz.landscape.ogm.Directory;
import net.explorviz.landscape.ogm.http.HttpEndpoint;
import net.explorviz.landscape.ogm.rpc.RpcSystem;
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
  private final Set<RpcSystem> rpcSystems = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<HttpEndpoint> httpEndpoints = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private Directory directory;

  public Scope() {
    // Empty constructor required by Neo4j OGM
  }

  public Scope(final String name) {
    this.name = name;
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

  public Set<RpcSystem> getRpcSystems() {
    return new HashSet<>(rpcSystems);
  }

  public Set<HttpEndpoint> getHttpEndpoints() {
    return new HashSet<>(httpEndpoints);
  }

  public Directory getDirectory() {
    return directory;
  }
}
