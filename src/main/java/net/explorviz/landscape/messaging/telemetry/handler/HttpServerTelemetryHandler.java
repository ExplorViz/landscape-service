package net.explorviz.landscape.messaging.telemetry.handler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.Map;
import net.explorviz.landscape.ogm.http.HttpEndpoint;
import net.explorviz.landscape.proto.HttpServerDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.util.otel.SpanFactory;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe server-side HTTP requests and
 * writes the corresponding nodes to the graph.
 */
@ApplicationScoped
public final class HttpServerTelemetryHandler implements TelemetryHandler {

  @Inject Tracer tracer;

  @Override
  @WithSpan
  public void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasHttpServerDescriptor()) {
      throw new IllegalArgumentException("HTTP server descriptor is required");
    }

    final HttpServerDescriptor descriptor = entity.getHttpServerDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureEndpointPathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create HTTP server entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureEndpointPath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create runtime HTTP server entity");
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
      final Session session, final TelemetryEntity entity, final HttpServerDescriptor descriptor) {

    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

        MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
        MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
        MERGE (sc)-[:CONTAINS]->(e:HTTPEndpoint {name: $endpointRoute})<-[:CONTAINS]-(commit)

        SET e.telemetryKey = $endpointTelemetryKey
        SET e.supportedMethods =
          [method IN coalesce(e.supportedMethods, []) WHERE method <> $method] + [$method]

        RETURN e;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "appName", descriptor.getApplicationName(),
            "scopeName", entity.getInstrumentationScope(),
            "endpointRoute", descriptor.getRoute(),
            "endpointTelemetryKey", descriptor.getTelemetryKey(),
            "method", descriptor.getMethod());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final HttpEndpoint result = session.queryForObject(HttpEndpoint.class, query, params);
    span.end();

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
      final Session session, final TelemetryEntity entity, final HttpServerDescriptor descriptor) {

    final String query =
        """
        MERGE (l:Landscape {tokenId: $tokenId})
        MERGE (l)-[:CONTAINS]->(a:Application {name: $appName})
        MERGE (a)-[:CONTAINS]->(sc:Scope {name: $scopeName})
        OPTIONAL CALL (sc) {
          MATCH (sc) WHERE NOT EXISTS {
            MATCH (sc)-[:CONTAINS]->(e:HTTPEndpoint {name: $endpointRoute})
            WHERE NOT (:Commit)-[:CONTAINS]->(e)
          }
          // Only executed if previous match was successful
          CREATE (sc)-[:CONTAINS]->(e:HTTPEndpoint {name: $endpointRoute})
        }
        MATCH (sc)-[:CONTAINS]->(e:HTTPEndpoint {name: $endpointRoute})
        WHERE NOT (:Commit)-[:CONTAINS]->(e)

        SET e.telemetryKey = $endpointTelemetryKey
        SET e.supportedMethods =
          [method IN coalesce(e.supportedMethods, []) WHERE method <> $method] + [$method]

        RETURN e;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "appName", descriptor.getApplicationName(),
            "scopeName", entity.getInstrumentationScope(),
            "endpointRoute", descriptor.getRoute(),
            "endpointTelemetryKey", descriptor.getTelemetryKey(),
            "method", descriptor.getMethod());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final HttpEndpoint result = session.queryForObject(HttpEndpoint.class, query, params);
    span.end();

    return result != null;
  }
}
