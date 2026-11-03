package net.explorviz.landscape.messaging.telemetry.handler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.Map;
import net.explorviz.landscape.ogm.http.HttpClient;
import net.explorviz.landscape.proto.HttpClientDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.util.otel.SpanFactory;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe client-side HTTP requests and
 * writes the corresponding nodes to the graph.
 */
@ApplicationScoped
public final class HttpClientTelemetryHandler implements TelemetryHandler {

  @Inject Tracer tracer;

  @Override
  @WithSpan
  public void saveEntity(final Session session, final TelemetryEntity entity) {
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
  private boolean ensureClientPathForCommit(
      final Session session, final TelemetryEntity entity, final HttpClientDescriptor descriptor) {

    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

        MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
        MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
        MERGE (sc)-[:CONTAINS]->(c:HTTPClient)<-[:CONTAINS]-(commit)

        SET c.telemetryKey = $telemetryKey
        RETURN c;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "appName", descriptor.getApplicationName(),
            "scopeName", entity.getInstrumentationScope(),
            "name", HttpClient.DISPLAY_NAME,
            "telemetryKey", descriptor.getTelemetryKey());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final HttpClient result = session.queryForObject(HttpClient.class, query, params);
    span.end();

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified HTTP client node exists, where the
   * client node should be the runtime version of that client, meaning it should not be contained in
   * any commit. All missing nodes along the path are created. For the client node, a telemetry key
   * is set regardless of whether the node previously existed.
   */
  private boolean ensureClientPath(
      final Session session, final TelemetryEntity entity, final HttpClientDescriptor descriptor) {

    final String query =
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
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "appName", descriptor.getApplicationName(),
            "scopeName", entity.getInstrumentationScope(),
            "name", HttpClient.DISPLAY_NAME,
            "telemetryKey", descriptor.getTelemetryKey());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final HttpClient result = session.queryForObject(HttpClient.class, query, params);
    span.end();

    return result != null;
  }
}
