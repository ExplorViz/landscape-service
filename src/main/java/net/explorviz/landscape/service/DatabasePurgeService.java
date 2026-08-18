package net.explorviz.landscape.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.repository.DatabasePurgeRepository;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@ApplicationScoped
public class DatabasePurgeService {

  private static final int DEFAULT_CHUNK_SIZE = 10_000;

  @Inject DatabasePurgeRepository databasePurgeRepository;

  @Inject SessionFactory sessionFactory;

  /**
   * Purges all data from the Neo4j database in chunks.
   *
   * @param chunkSize maximum number of nodes deleted per chunk
   * @return total number of nodes deleted
   */
  public int purgeDatabaseInChunks(final int chunkSize) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      final int deleted = databasePurgeRepository.purgeDatabaseInChunks(session, chunkSize);
      tx.commit();
      Log.infof("Purged %d nodes from the database in chunks of %d", deleted, chunkSize);
      return deleted;
    } catch (Exception e) { // NOPMD
      Log.error("Failed to purge database", e);
      throw e;
    }
  }

  public int purgeDatabaseInChunks() {
    return purgeDatabaseInChunks(DEFAULT_CHUNK_SIZE);
  }
}
