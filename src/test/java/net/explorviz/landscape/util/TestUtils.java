package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.arc.Arc;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.repository.FileRevisionIdCache;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

public class TestUtils {

  private static final List<String> SCHEMA_STATEMENTS =
      List.of(
          "CREATE CONSTRAINT landscape_token_id IF NOT EXISTS FOR (l:Landscape) REQUIRE l.tokenId"
              + " IS UNIQUE",
          "CREATE INDEX trace_trace_id IF NOT EXISTS FOR (t:Trace) ON (t.traceId)",
          "CREATE INDEX span_span_id IF NOT EXISTS FOR (s:Span) ON (s.spanId)",
          "CREATE INDEX directory_name IF NOT EXISTS FOR (d:Directory) ON (d.name)",
          "CREATE INDEX file_revision_hash_name IF NOT EXISTS FOR (f:FileRevision) ON (f.hash,"
              + " f.name)",
          "CREATE INDEX file_revision_repo_file_path_hash IF NOT EXISTS FOR (f:FileRevision) ON"
              + " (f.repoName, f.filePath, f.hash)",
          "CREATE INDEX file_revision_lookup_key IF NOT EXISTS FOR (f:FileRevision) ON"
              + " (f.lookupKey)",
          "CREATE INDEX function_name IF NOT EXISTS FOR (f:Function) ON (f.name)",
          "CREATE INDEX commit_hash IF NOT EXISTS FOR (c:Commit) ON (c.hash)");

  private TestUtils() {}

  /**
   * Clears all graph data and reapplies the schema indexes used in production. Uses batched deletes
   * so cleanup remains feasible even when a large development database is accidentally targeted.
   */
  public static void resetDatabase(final Session session) {
    clearDatabase(session);
    ensureSchema(session);
    clearFileRevisionIdCache();
  }

  private static void clearFileRevisionIdCache() {
    final var container = Arc.container();
    if (container != null && container.isRunning()) {
      final var cache = container.instance(FileRevisionIdCache.class);
      if (cache.isAvailable()) {
        cache.get().clear();
      }
    }
  }

  public static void clearDatabase(final Session session) {
    session.query(
        """
        CALL apoc.periodic.iterate(
          'MATCH (n) RETURN id(n) AS id',
          'MATCH (n) WHERE id(n) = id DETACH DELETE n',
          {batchSize: 10000, parallel: false}
        ) YIELD batches, total
        RETURN batches, total
        """,
        Map.of());
    session.clear();
  }

  public static void ensureSchema(final Session session) {
    SCHEMA_STATEMENTS.forEach(statement -> session.query(statement, Map.of()));
  }

  public static Map<String, Object> getNodeCountMap(Session session) {
    Result result =
        session.query(
            """
            RETURN
              COUNT {(:Landscape)} AS landscapes,
              COUNT {(:Repository)} AS repositories,
              COUNT {(:Branch)} AS branches,
              COUNT {(:Commit)} AS commits,
              COUNT {(:Tag)} AS tags,
              COUNT {(:Application)} AS applications,
              COUNT {(:Directory)} AS directories,
              COUNT {(:FileRevision)} AS files,
              COUNT {(:Clazz)} AS classes,
              COUNT {(:Field)} AS fields,
              COUNT {(:Function)} AS functions,
              COUNT {(:Parameter)} AS parameters,
              COUNT {(:Trace)} AS traces,
              COUNT {(:Span)} AS spans;
            """,
            Map.of());

    return result.queryResults().iterator().next();
  }

  public static void assertNodeCounts(Session session, Map<String, Long> expected) {
    Map<String, Object> actual = getNodeCountMap(session);
    for (NodeCountType type : NodeCountType.values()) {
      String key = type.key();

      long expectedValue = expected.getOrDefault(key, 0L);
      long actualValue = (Long) actual.getOrDefault(key, 0L);

      assertEquals(expectedValue, actualValue, "Mismatch for type: " + key);
    }
  }
}
