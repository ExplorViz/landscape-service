package net.explorviz.landscape.messaging.telemetry.handler;

import io.quarkus.logging.Log;
import java.util.Map;
import net.explorviz.landscape.ogm.rpc.RpcClient;
import net.explorviz.landscape.proto.RpcClientDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe client-side remote procedure calls
 * and writes the corresponding nodes to the graph.
 */
public final class RpcClientTelemetryHandler {

  private RpcClientTelemetryHandler() {}

  public static void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasRpcClientDescriptor()) {
      throw new IllegalArgumentException("RPC client descriptor is required");
    }

    final RpcClientDescriptor descriptor = entity.getRpcClientDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureClientPathForCommit(session, entity, descriptor);

      if (success) {
        return;
      }
      Log.debugf(
          "Could not create RPC entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureClientPath(session, entity, descriptor);

    if (!success) {
      Log.errorf("Failed to create runtime RPC entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified RPC client node exists, where the
   * client should be the runtime version of that client, meaning it should not be contained in any
   * commit. All missing nodes along the path are created. For the client node, a telemetry key is
   * set regardless of whether it previously existed.
   */
  private static boolean ensureClientPath(
      final Session session, final TelemetryEntity entity, final RpcClientDescriptor descriptor) {

    final RpcClient result =
        session.queryForObject(
            RpcClient.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})

            OPTIONAL CALL (sc) {
              WITH sc
              WHERE $systemName <> ""
              MERGE (sc)-[:CONTAINS]->(sys:RPCSystem {name: $systemName})
              RETURN sys
            }

            WITH coalesce(sys, sc) AS parent

            OPTIONAL CALL (parent) {
              MATCH (parent) WHERE NOT EXISTS {
                MATCH (parent)-[:CONTAINS]->(c:RPCClient {name: $name})
                WHERE NOT (:Commit)-[:CONTAINS]->(c)
              }
              // Only executed if previous match was successful
              CREATE (parent)-[:CONTAINS]->(:RPCClient {name: $name})
            }
            MATCH (parent)-[:CONTAINS]->(c:RPCClient {name: $name})
            WHERE NOT (:Commit)-[:CONTAINS]->(c)

            SET c.telemetryKey = $telemetryKey

            RETURN c;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "systemName", descriptor.getSystemName(),
                "name", RpcClient.DISPLAY_NAME,
                "telemetryKey", descriptor.getTelemetryKey()));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified RPC client node exists, where the
   * client should be tied to a specific commit. For this to be successful, the landscape node must
   * already exist and contain a commit node with the specified hash via some repository. All
   * remaining missing nodes along the path are created. For the client node, a telemetry key is set
   * regardless of whether it previously existed.
   */
  private static boolean ensureClientPathForCommit(
      final Session session, final TelemetryEntity entity, final RpcClientDescriptor descriptor) {

    final RpcClient result =
        session.queryForObject(
            RpcClient.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})

            OPTIONAL CALL (sc) {
              WITH sc
              WHERE $systemName <> ""
              MERGE (sc)-[:CONTAINS]->(sys:RPCSystem {name: $systemName})
              RETURN sys
            }

            WITH coalesce(sys, sc) AS parent
            MERGE (parent)-[:CONTAINS]->(c:RPCClient {name: $name})<-[:CONTAINS]-(commit)
            SET c.telemetryKey = $telemetryKey

            RETURN c;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "systemName", descriptor.getSystemName(),
                "name", RpcClient.DISPLAY_NAME,
                "telemetryKey", descriptor.getTelemetryKey()));

    return result != null;
  }
}
