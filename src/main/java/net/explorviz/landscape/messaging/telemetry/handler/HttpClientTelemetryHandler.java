package net.explorviz.landscape.messaging.telemetry.handler;

import io.quarkus.logging.Log;
import java.util.Map;
import net.explorviz.landscape.ogm.http.HttpClient;
import net.explorviz.landscape.proto.HttpClientDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe client-side HTTP requests and
 * writes the corresponding nodes to the graph.
 */
public final class HttpClientTelemetryHandler {

  private HttpClientTelemetryHandler() {}

  public static void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasHttpClientDescriptor()) {
      throw new IllegalArgumentException("HTTP client descriptor is required");
    }

    final HttpClientDescriptor descriptor = entity.getHttpClientDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureClientPathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create HTTP client entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureClientPath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create runtime HTTP client entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified HTTP client node exists, where the
   * client node should be tied to a specific commit. For this to be successful, the landscape node
   * must already exist and contain a commit node with the specified hash via some repository. All
   * remaining missing nodes along the path are created. For the client node, a telemetry key is set
   * regardless of whether the node previously existed.
   */
  private static boolean ensureClientPathForCommit(
      final Session session, final TelemetryEntity entity, final HttpClientDescriptor descriptor) {

    final HttpClient result =
        session.queryForObject(
            HttpClient.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            MERGE (sc)-[:CONTAINS]->(c:HTTPClient)<-[:CONTAINS]-(commit)

            SET c.telemetryKey = $telemetryKey
            RETURN c;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "name", HttpClient.DISPLAY_NAME,
                "telemetryKey", descriptor.getTelemetryKey()));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified HTTP client node exists, where the
   * client node should be the runtime version of that client, meaning it should not be contained in
   * any commit. All missing nodes along the path are created. For the client node, a telemetry key
   * is set regardless of whether the node previously existed.
   */
  private static boolean ensureClientPath(
      final Session session, final TelemetryEntity entity, final HttpClientDescriptor descriptor) {

    final HttpClient result =
        session.queryForObject(
            HttpClient.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            OPTIONAL CALL (sc) {
              MATCH (sc) WHERE NOT EXISTS {
                MATCH (sc)-[:CONTAINS]->(c:HTTPClient {name: $name})
                WHERE NOT (:Commit)-[:CONTAINS]->(c)
              }
              // Only executed if previous match was successful
              CREATE (sc)-[:CONTAINS]->(:HTTPClient {name: $name})
            }
            MATCH (sc)-[:CONTAINS]->(c:HTTPClient {name: $name})
            WHERE NOT (:Commit)-[:CONTAINS]->(c)

            SET c.telemetryKey = $telemetryKey

            RETURN c;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "name", HttpClient.DISPLAY_NAME,
                "telemetryKey", descriptor.getTelemetryKey()));

    return result != null;
  }
}
