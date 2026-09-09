package net.explorviz.landscape.ogm.rpc;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a namespace / package within which remote procedure call services reside. */
@NodeEntity(label = "RPCSystem")
public class RpcNamespace {

  @Id @GeneratedValue private Long id;

  private String name;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<RpcNamespace> namespaces = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<RpcService> services = new HashSet<>();

  public RpcNamespace() {
    // Empty constructor required by Neo4j OGM
  }

  public RpcNamespace(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public Set<RpcNamespace> getContainedNamespaces() {
    return new HashSet<>(namespaces);
  }

  public Set<RpcService> getServices() {
    return new HashSet<>(services);
  }

  public void addNamespace(final RpcNamespace namespace) {
    namespaces.add(namespace);
  }

  public void addService(final RpcService service) {
    services.add(service);
  }
}
