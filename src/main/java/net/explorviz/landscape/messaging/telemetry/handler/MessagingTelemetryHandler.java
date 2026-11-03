package net.explorviz.landscape.messaging.telemetry.handler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.Map;
import net.explorviz.landscape.ogm.messaging.MessagingDestination;
import net.explorviz.landscape.proto.MessagingDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.util.otel.SpanFactory;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe message broker operations and
 * writes the corresponding nodes to the graph.
 */
@ApplicationScoped
public final class MessagingTelemetryHandler implements TelemetryHandler {

  @Inject Tracer tracer;

  @Override
  @WithSpan
  public void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasMessagingDescriptor()) {
      throw new IllegalArgumentException("Messaging descriptor is required");
    }

    final MessagingDescriptor descriptor = entity.getMessagingDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureMessagingDestinationPathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create messaging entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureMessagingDestinationPath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create runtime messaging entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified messaging destination node exists,
   * where the destination node should be tied to a specific commit. For this to be successful, the
   * landscape node must already exist and contain a commit node with the specified hash via some
   * repository. All remaining missing nodes along the path are created. For the messaging
   * destination node, a telemetry key is set regardless of whether the node previously existed.
   */
  private boolean ensureMessagingDestinationPathForCommit(
      final Session session, final TelemetryEntity entity, final MessagingDescriptor descriptor) {

    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

        MERGE (l)-[:CONTAINS]->(sys:Application {name: $systemName})
        MERGE (sys)
          -[:CONTAINS]->(dest:MessagingDestination {name: $destinationName})
          <-[:CONTAINS]-(commit)
        SET dest.telemetryKey = $telemetryKey

        RETURN dest;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "systemName", descriptor.getSystemName(),
            "telemetryKey", descriptor.getTelemetryKey(),
            "destinationName", descriptor.getDestinationName());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final MessagingDestination result =
        session.queryForObject(MessagingDestination.class, query, params);
    span.end();

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified messaging destination node exists,
   * where the destination should be the runtime version of that destination, meaning it should not
   * be contained in any commit. All missing nodes along the path are created. For the destination,
   * a telemetry key is set regardless of whether the node previously existed.
   */
  private boolean ensureMessagingDestinationPath(
      final Session session, final TelemetryEntity entity, final MessagingDescriptor descriptor) {

    final String query =
        """
        MERGE (l:Landscape {tokenId: $tokenId})
        MERGE (l)-[:CONTAINS]->(sys:Application {name: $systemName})

        OPTIONAL CALL (sys) {
          MATCH (sys) WHERE NOT EXISTS {
            MATCH (sys)-[:CONTAINS]->(dest:MessagingDestination {name: $destinationName})
            WHERE NOT (:Commit)-[:CONTAINS]->(dest)
          }
          // Only executed if previous match was successful
          CREATE (sys)-[:CONTAINS]->(dest:MessagingDestination {name: $destinationName})
        }
        MATCH (sys)-[:CONTAINS]->(dest:MessagingDestination {name: $destinationName})
        WHERE NOT (:Commit)-[:CONTAINS]->(dest)
        SET dest.telemetryKey = $telemetryKey

        RETURN dest;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "systemName", descriptor.getSystemName(),
            "telemetryKey", descriptor.getTelemetryKey(),
            "destinationName", descriptor.getDestinationName());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final MessagingDestination result =
        session.queryForObject(MessagingDestination.class, query, params);
    span.end();

    return result != null;
  }
}
