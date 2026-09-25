package net.explorviz.landscape.ogm.rpc;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents an RPC client for a particular RPC system, making requests to a server's API. */
@NodeEntity(label = "RPCClient")
public class RpcClient {

  /**
   * Name that is given to buildings representing RPC client entities by default, since these do not
   * have a natural name that can be derived from its attributes.
   */
  public static final String DISPLAY_NAME = "RPC Client";

  @Id @GeneratedValue private Long id;

  @SuppressWarnings({"PMD.FinalFieldCouldBeStatic", "FieldCanBeLocal"})
  private final String name = DISPLAY_NAME;

  /**
   * Identifier for looking up telemetry data related to this client (e.g. finding communication).
   * This value is not unique across different commits; use in conjunction with the commit hash to
   * find telemetry for one specific commit only.
   */
  private String telemetryKey;

  public String getName() {
    return name;
  }
}
