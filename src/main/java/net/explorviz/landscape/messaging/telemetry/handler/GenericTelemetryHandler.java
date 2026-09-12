package net.explorviz.landscape.messaging.telemetry.handler;

import io.quarkus.logging.Log;
import java.util.Map;
import net.explorviz.landscape.ogm.otel.GenericTelemetryEntity;
import net.explorviz.landscape.proto.GenericServiceDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe services that cannot be classified
 * more precisely and writes the corresponding nodes to the graph.
 */
public final class GenericTelemetryHandler {

  private GenericTelemetryHandler() {}

  public static void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasGenericServiceDescriptor()) {
      throw new IllegalArgumentException("Generic descriptor is required");
    }

    final GenericServiceDescriptor descriptor = entity.getGenericServiceDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureEntityPathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create generic entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureEntityPath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create generic entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified generic entity node exists, where
   * the entity should be the runtime version of that entity, meaning it should not be contained in
   * any commit. All missing nodes along the path are created. For the endpoint, a telemetry key is
   * set regardless of whether the node previously existed.
   */
  private static boolean ensureEntityPath(
      final Session session,
      final TelemetryEntity entity,
      final GenericServiceDescriptor descriptor) {

    final GenericTelemetryEntity result =
        session.queryForObject(
            GenericTelemetryEntity.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(app:Application {name: $serviceName})
            MERGE (app)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            CALL (sc) {
              MATCH (sc) WHERE NOT EXISTS {
                MATCH (sc)-[:CONTAINS]->(e:GenericTelemetryEntity {name: $name})
                WHERE NOT (:Commit)-[:CONTAINS]->(e)
              }
              // Only executed if previous match was successful
              CREATE (sc)-[:CONTAINS]->(e:GenericTelemetryEntity {name: $name})
            }
            MATCH (sc)-[:CONTAINS]->(e:GenericTelemetryEntity {name: $name})
            WHERE NOT (:Commit)-[:CONTAINS]->(e)

            SET e.telemetryKey = $telemetryKey

            RETURN e;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "serviceName", descriptor.getServiceName(),
                "scopeName", entity.getInstrumentationScope(),
                "name", GenericTelemetryEntity.DISPLAY_NAME,
                "telemetryKey", descriptor.getServiceTelemetryKey()));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified generic entity node exists, where
   * the entity node should be tied to a specific commit. For this to be successful, the landscape
   * node must already exist and contain a commit node with the specified hash via some repository.
   * All remaining missing nodes along the path are created. For the entity node, a telemetry key is
   * set regardless of whether the node previously existed.
   */
  private static boolean ensureEntityPathForCommit(
      final Session session,
      final TelemetryEntity entity,
      final GenericServiceDescriptor descriptor) {

    final GenericTelemetryEntity result =
        session.queryForObject(
            GenericTelemetryEntity.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

            MERGE (l)-[:CONTAINS]->(app:Application {name: $serviceName})
            MERGE (app)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            MERGE (sc)-[:CONTAINS]->(e:GenericTelemetryEntity)<-[:CONTAINS]-(commit)

            SET e.telemetryKey = $telemetryKey

            RETURN e;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "serviceName", descriptor.getServiceName(),
                "scopeName", entity.getInstrumentationScope(),
                "telemetryKey", descriptor.getServiceTelemetryKey()));

    return result != null;
  }
}
