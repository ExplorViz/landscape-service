package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class DatabasePurgeRepository {

  private static final int DEFAULT_CHUNK_SIZE = 10_000;

  /**
   * Deletes all nodes in the database in chunks to avoid large transactions.
   *
   * @param session open Neo4j session
   * @param chunkSize maximum number of nodes deleted per chunk
   * @return total number of nodes deleted
   */
  public int purgeDatabaseInChunks(final Session session, final int chunkSize) {
    final int effectiveChunkSize = chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    int totalDeleted = 0;

    while (true) {
      final Long remaining =
          session.queryForObject(Long.class, "MATCH (n) RETURN count(n) AS count", Map.of());
      if (remaining == null || remaining == 0) {
        break;
      }

      session.query(
          """
          MATCH (n)
          WITH n LIMIT $limit
          DETACH DELETE n
          """,
          Map.of("limit", effectiveChunkSize));

      final int deletedThisChunk = (int) Math.min(remaining, effectiveChunkSize);
      totalDeleted += deletedThisChunk;
    }

    return totalDeleted;
  }
}
