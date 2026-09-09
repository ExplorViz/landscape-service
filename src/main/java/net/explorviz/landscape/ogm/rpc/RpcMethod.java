package net.explorviz.landscape.ogm.rpc;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents a method within a remote procedure call (RPC) service interface. */
@NodeEntity(label = "RPCMethod")
public class RpcMethod {

  @Id @GeneratedValue private Long id;

  private String name;

  /**
   * Identifier for looking up telemetry data related to this method (e.g. finding communication).
   * This value is not unique across different commits; use in conjunction with the commit hash to
   * find telemetry for one specific commit only.
   */
  private String telemetryKey;

  public RpcMethod() {
    // Empty constructor required by Neo4j OGM
  }

  public RpcMethod(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
