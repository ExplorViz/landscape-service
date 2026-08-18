package net.explorviz.landscape.repository;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class DatabasePurgeRepositoryTest {

  @Inject DatabasePurgeRepository databasePurgeRepository;

  @Inject SessionFactory sessionFactory;

  private Session session;

  @BeforeEach
  void setUp() {
    session = sessionFactory.openSession();
    resetDatabase(session);
  }

  @Test
  void purgeDatabaseInChunksDeletesAllNodes() {
    session.query(
        """
        CREATE (n:TempNode {name: 'one'})
        CREATE (m:TempNode {name: 'two'})
        """,
        Map.of());

    final Long nodesBefore =
        session.queryForObject(Long.class, "MATCH (n) RETURN count(n) AS count", Map.of());
    assertEquals(2L, nodesBefore);

    final int deleted = databasePurgeRepository.purgeDatabaseInChunks(session, 1);
    assertEquals(2, deleted);

    final Long nodesAfter =
        session.queryForObject(Long.class, "MATCH (n) RETURN count(n) AS count", Map.of());
    assertEquals(0L, nodesAfter);
  }
}
