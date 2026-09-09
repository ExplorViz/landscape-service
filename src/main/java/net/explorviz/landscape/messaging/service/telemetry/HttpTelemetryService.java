package net.explorviz.landscape.messaging.service.telemetry;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import net.explorviz.landscape.ogm.http.HttpEndpoint;
import net.explorviz.landscape.proto.HttpDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe HTTP requests and writes the
 * corresponding nodes to the graph.
 */
@ApplicationScoped
public class HttpTelemetryService {
  public void saveEntity(
      final Session session, final TelemetryEntity entity, final HttpDescriptor descriptor) {

    if (descriptor.getApplicationName().isBlank()) {
      System.out.println("yoo");
      System.out.println(descriptor);
    }

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureEndpointPathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create HTTP entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureEndpointPath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create runtime HTTP entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified HTTP endpoint node exists, where the
   * endpoint node should be tied to a specific commit. For this to be successful, the landscape
   * node must already exist and contain a commit node with the specified hash via some repository.
   * All remaining missing nodes along the path are created. For the endpoint node, a telemetry key
   * is set regardless of whether the node previously existed. The endpoint's supported methods are
   * updated to contain that of the provided entity descriptor.
   */
  private boolean ensureEndpointPathForCommit(
      final Session session, final TelemetryEntity entity, final HttpDescriptor descriptor) {

    final HttpEndpoint result =
        session.queryForObject(
            HttpEndpoint.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            MERGE (sc)-[:CONTAINS]->(e:HTTPEndpoint {route: $endpointRoute})<-[:CONTAINS]-(commit)

            SET e.telemetryKey = $endpointTelemetryKey
            SET e.supportedMethods =
              [method IN coalesce(e.supportedMethods, []) WHERE method <> $method] + [$method]

            RETURN e;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "endpointRoute", descriptor.getRoute(),
                "endpointTelemetryKey", descriptor.getTelemetryKey(),
                "method", descriptor.getMethod()));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified HTTP endpoint node exists, where the
   * endpoint should be the runtime version of that endpoint, meaning it should not be contained in
   * any commit. All missing nodes along the path are created. For the endpoint, a telemetry key is
   * set regardless of whether the node previously existed. The endpoint's supported methods are
   * updated to contain that of the provided entity descriptor.
   */
  private boolean ensureEndpointPath(
      final Session session, final TelemetryEntity entity, final HttpDescriptor descriptor) {

    final HttpEndpoint result =
        session.queryForObject(
            HttpEndpoint.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
            MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
            OPTIONAL CALL (sc) {
              MATCH (sc) WHERE NOT EXISTS {
                MATCH (sc)-[:CONTAINS]->(e:HTTPEndpoint {route: $endpointRoute})
                WHERE NOT (:Commit)-[:CONTAINS]->(e)
              }
              CREATE (sc)-[:CONTAINS]->(e:HTTPEndpoint {route: $endpointRoute})
            }
            MATCH (sc)-[:CONTAINS]->(e:HTTPEndpoint {route: $endpointRoute})
            WHERE NOT (:Commit)-[:CONTAINS]->(e)

            SET e.telemetryKey = $endpointTelemetryKey
            SET e.supportedMethods =
              [method IN coalesce(e.supportedMethods, []) WHERE method <> $method] + [$method]

            RETURN e;
            """,
            Map.of(
                "tokenId", entity.getLandscapeTokenId(),
                "commitHash", entity.getGitCommitHash(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "endpointRoute", descriptor.getRoute(),
                "endpointTelemetryKey", descriptor.getTelemetryKey(),
                "method", descriptor.getMethod()));

    return result != null;
  }
}
