package net.explorviz.landscape.ogm.rpc;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a remote procedure call service, such as those found in gRPC. */
@NodeEntity(label = "RPCService")
public class RpcService {

  @Id @GeneratedValue private Long id;

  private String name;

  /**
   * Identifier for looking up telemetry data related to this service (e.g. finding communication).
   * This value is not unique across different commits; use in conjunction with the commit hash to
   * find telemetry for one specific commit only.
   */
  private String telemetryKey;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<RpcMethod> methods = new HashSet<>();

  public RpcService() {
    // Empty constructor required by Neo4j OGM
  }

  public RpcService(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public String getTelemetryKey() {
    return telemetryKey;
  }

  public void setTelemetryKey(final String telemetryKey) {
    this.telemetryKey = telemetryKey;
  }

  public Set<RpcMethod> getMethods() {
    return new HashSet<>(methods);
  }

  public void addMethod(final RpcMethod method) {
    methods.add(method);
  }
}
