package net.explorviz.landscape.messaging.telemetry.handler;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.Map;
import net.explorviz.landscape.ogm.database.DatabaseSystem;
import net.explorviz.landscape.proto.DatabaseDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.util.otel.SpanFactory;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe database queries and writes the
 * corresponding nodes to the graph.
 */
@ApplicationScoped
public final class DatabaseTelemetryHandler implements TelemetryHandler {

  @Inject Tracer tracer;

  @Override
  @WithSpan
  public void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasDatabaseDescriptor()) {
      throw new IllegalArgumentException("Database descriptor is required");
    }

    final DatabaseDescriptor descriptor = entity.getDatabaseDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success = ensureDatabasePathForCommit(session, entity, descriptor);
      if (success) {
        return;
      }
      Log.debugf(
          "Could not create database entity for commit %s, creating runtime entity instead",
          entity.getGitCommitHash());
    }

    final boolean success = ensureDatabasePath(session, entity, descriptor);
    if (!success) {
      Log.errorf("Failed to create runtime database entity");
    }
  }

  /**
   * Ensures that a path from a landscape node to the specified database system (and optionally
   * database and / or table) node exists, where the database system node should be tied to a
   * specific commit. For this to be successful, the landscape node must already exist and contain a
   * commit node with the specified hash via some repository. All remaining missing nodes along the
   * path are created. For the database system node, a telemetry key is set regardless of whether
   * the node previously existed.
   */
  private boolean ensureDatabasePathForCommit(
      final Session session, final TelemetryEntity entity, final DatabaseDescriptor descriptor) {

    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(commit:Commit {hash: $commitHash})

        MERGE (l)-[:CONTAINS]->(sys:DatabaseSystem {name: $systemName})<-[:CONTAINS]-(commit)
        SET sys.telemetryKey = $telemetryKey

        OPTIONAL CALL (sys) {
          WITH sys
          WHERE $databaseName <> ""
          MERGE (sys)-[:CONTAINS]->(db:Database {name: $databaseName})
          RETURN db
        }

        OPTIONAL CALL (db, sys) {
          WITH coalesce(db, sys) AS parent
          WHERE $tableName <> ""
          MERGE (parent)-[:CONTAINS]->(:DatabaseTable {name: $tableName})
        }

        RETURN sys;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "systemName", descriptor.getSystemName(),
            "telemetryKey", descriptor.getTelemetryKey(),
            "databaseName", descriptor.getDatabaseName(),
            "tableName", descriptor.getTableName());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final DatabaseSystem result = session.queryForObject(DatabaseSystem.class, query, params);
    span.end();

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified database system node exists, where
   * the database system should be the runtime version of that system, meaning it should not be
   * contained in any commit. All missing nodes along the path are created. If specified, nodes for
   * a database and / or table within the system are also created if they do not yet exist. For the
   * database system, a telemetry key is set regardless of whether the node previously existed.
   */
  private boolean ensureDatabasePath(
      final Session session, final TelemetryEntity entity, final DatabaseDescriptor descriptor) {

    final String query =
        """
        MERGE (l:Landscape {tokenId: $tokenId})

        OPTIONAL CALL (l) {
          MATCH (l) WHERE NOT EXISTS {
            MATCH (l)-[:CONTAINS]->(sys:DatabaseSystem {name: $systemName})
            WHERE NOT (:Commit)-[:CONTAINS]->(sys)
          }
          // Only executed if previous match was successful
          CREATE (l)-[:CONTAINS]->(sys:DatabaseSystem {name: $systemName})
        }
        MATCH (l)-[:CONTAINS]->(sys:DatabaseSystem {name: $systemName})
        WHERE NOT (:Commit)-[:CONTAINS]->(sys)
        SET sys.telemetryKey = $telemetryKey

        OPTIONAL CALL (sys) {
          WITH sys
          WHERE $databaseName <> ""
          MERGE (sys)-[:CONTAINS]->(db:Database {name: $databaseName})
          RETURN db
        }

        OPTIONAL CALL (db, sys) {
          WITH coalesce(db, sys) AS parent
          WHERE $tableName <> ""
          MERGE (parent)-[:CONTAINS]->(:DatabaseTable {name: $tableName})
        }

        RETURN sys;
        """;

    final Map<String, Serializable> params =
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "commitHash", entity.getGitCommitHash(),
            "systemName", descriptor.getSystemName(),
            "telemetryKey", descriptor.getTelemetryKey(),
            "databaseName", descriptor.getDatabaseName(),
            "tableName", descriptor.getTableName());

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MERGE", query, params);
    final DatabaseSystem result = session.queryForObject(DatabaseSystem.class, query, params);
    span.end();

    return result != null;
  }
}
