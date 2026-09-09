package net.explorviz.landscape.messaging.service.telemetry;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import net.explorviz.landscape.ogm.rpc.RpcMethod;
import net.explorviz.landscape.proto.RpcDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe remote procedure calls and writes
 * the corresponding nodes to the graph.
 */
@ApplicationScoped
public class RpcTelemetryService {
  public void saveEntity(
      final Session session, final TelemetryEntity entity, final RpcDescriptor descriptor) {

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureMethodPathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create RPC entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureMethodPath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create runtime RPC entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified RPC method node exists, where the
   * service containing the method should be tied to a specific commit. For this to be successful,
   * the landscape node must already exist and contain a commit node with the specified hash via
   * some repository. All remaining missing nodes along the path are created. For the service and
   * method nodes, a telemetry key is set regardless of whether the nodes previously existed.
   */
  private boolean ensureMethodPathForCommit(
      final Session session, final TelemetryEntity entity, final RpcDescriptor descriptor) {

    final String[] servicePath = descriptor.getServiceName().split("\\.");

    final RpcMethod result =
        session.queryForObject(
            RpcMethod.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            MERGE (sc)-[:CONTAINS]->(sys:RPCSystem {name: $systemName})

            // Find longest service path match
            MATCH p = (sys)-[:CONTAINS]->*(deepestNode:RPCSystem|RPCNamespace|RPCService)
            WHERE
              all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $servicePath[j-1])
              AND (length(p) < size($servicePath) XOR "RPCService" IN labels(deepestNode))
              AND ((commit)-[:CONTAINS]->(deepestNode) XOR NOT "RPCService" IN labels(deepestNode))
            WITH deepestNode, p, commit
            ORDER BY length(p) DESC
            LIMIT 1

            // Create missing namespaces + service, if necessary
            WITH *, $servicePath[length(p)..] AS remainingServicePath
            OPTIONAL CALL (*) {
              UNWIND [x in range(0, size(remainingServicePath)-1) | x] AS idx
              CREATE (n:RPCNamespace {name: remainingServicePath[idx]})
              ORDER BY idx ASC
              WITH collect(n) AS newNodes
              WITH [deepestNode] + newNodes AS nodes
              CALL apoc.nodes.link(nodes, "CONTAINS")
              WITH last(nodes) AS lastCreated
              REMOVE lastCreated:RPCNamespace
              SET lastCreated:RPCService
              CREATE (commit)-[:CONTAINS]->(lastCreated)
              RETURN lastCreated
            }
            WITH coalesce(lastCreated, deepestNode) AS service
            SET service.telemetryKey = $serviceTelemetryKey

            MERGE (service)-[:CONTAINS]->(m:RPCMethod {name: $methodName})
            SET m.telemetryKey = $methodTelemetryKey
            RETURN m;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "systemName", descriptor.getSystemName(),
                "servicePath", servicePath,
                "serviceTelemetryKey", descriptor.getServiceTelemetryKey(),
                "methodName", descriptor.getMethodName(),
                "methodTelemetryKey", descriptor.getMethodTelemetryKey()));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified RPC method node exists, where the
   * service containing the method should be the runtime version of that service, meaning it should
   * not be contained in any commit. All missing nodes along the path are created. For the service
   * and method nodes, a telemetry key is set regardless of whether the nodes previously existed.
   */
  private boolean ensureMethodPath(
      final Session session, final TelemetryEntity entity, final RpcDescriptor descriptor) {

    final String[] servicePath = descriptor.getServiceName().split("\\.");

    final RpcMethod result =
        session.queryForObject(
            RpcMethod.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            MERGE (sc)-[:CONTAINS]->(sys:RPCSystem {name: $systemName})

            // Find longest service path match
            MATCH p = (sys)-[:CONTAINS]->*(deepestNode:RPCSystem|RPCNamespace|RPCService)
            WHERE
              all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $servicePath[j-1])
              AND (length(p) < size($servicePath) XOR "RPCService" IN labels(deepestNode))
              AND NOT (:Commit)-[:CONTAINS]->(deepestNode)
            WITH deepestNode, p
            ORDER BY length(p) DESC
            LIMIT 1

            // Create missing namespaces + service, if necessary
            WITH *, $servicePath[length(p)..] AS remainingServicePath
            OPTIONAL CALL (*) {
              UNWIND [x in range(0, size(remainingServicePath)-1) | x] AS idx
              CREATE (n:RPCNamespace {name: remainingServicePath[idx]})
              ORDER BY idx ASC
              WITH collect(n) AS newNodes
              WITH [deepestNode] + newNodes AS nodes
              CALL apoc.nodes.link(nodes, "CONTAINS")
              WITH last(nodes) AS lastCreated
              REMOVE lastCreated:RPCNamespace
              SET lastCreated:RPCService
              RETURN lastCreated
            }
            WITH coalesce(lastCreated, deepestNode) AS service
            SET service.telemetryKey = $serviceTelemetryKey

            MERGE (service)-[:CONTAINS]->(m:RPCMethod {name: $methodName})
            SET m.telemetryKey = $methodTelemetryKey
            RETURN m;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "servicePath", servicePath,
                "serviceTelemetryKey", descriptor.getServiceTelemetryKey(),
                "methodName", descriptor.getMethodName(),
                "methodTelemetryKey", descriptor.getMethodTelemetryKey(),
                "systemName", descriptor.getSystemName()));

    return result != null;
  }
}
