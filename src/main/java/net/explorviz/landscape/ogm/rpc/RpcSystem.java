package net.explorviz.landscape.ogm.rpc;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a system for remote procedure calls (e.g. gRPC, Dubbo, JSON-RPC). */
@NodeEntity(label = "RPCSystem")
public class RpcSystem {

  @Id @GeneratedValue private Long id;

  private String name;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<RpcNamespace> namespaces = new HashSet<>();

  /** Contains those RPC services for this system which do not belong to a namespace. */
  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<RpcService> services = new HashSet<>();

  public RpcSystem() {
    // Empty constructor required by Neo4j OGM
  }

  public RpcSystem(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public Set<RpcNamespace> getNamespaces() {
    return new HashSet<>(namespaces);
  }

  public Set<RpcService> getServices() {
    return new HashSet<>(services);
  }
}
