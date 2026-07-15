package net.explorviz.landscape.repository;

import static java.util.stream.Collectors.toMap;
import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class SocialMetricsRepositoryTest {

  private static final String SEED =
      """
      CREATE (l:Landscape {tokenId:'t1'})-[:CONTAINS]->(r:Repository {name:'repo1'})
      CREATE (alice:Contributor {gitUsername:'alice', githubLogin:'alice', email:'a@x'})
      CREATE (bob:Contributor   {gitUsername:'bob',   githubLogin:'bob',   email:'b@x'})
      CREATE (r)-[:CONTAINS]->(c1:Commit {hash:'c1', commitDate:1000})
      CREATE (r)-[:CONTAINS]->(c2:Commit {hash:'c2', commitDate:2000})
      CREATE (r)-[:CONTAINS]->(c3:Commit {hash:'c3', commitDate:3000})
      CREATE (alice)-[:AUTHORED]->(c1)
      CREATE (alice)-[:AUTHORED]->(c2)
      CREATE (bob)-[:AUTHORED]->(c3)
      // A.java — three revisions, one filePath; changed in c1,c2,c3
      CREATE (a1:FileRevision {name:'A.java', hash:'a1', filePath:'src/A.java', `metrics.lineCount`:100.0})
      CREATE (a2:FileRevision {name:'A.java', hash:'a2', filePath:'src/A.java', `metrics.lineCount`:110.0})
      CREATE (a3:FileRevision {name:'A.java', hash:'a3', filePath:'src/A.java', `metrics.lineCount`:120.0})
      CREATE (c1)-[:ADDED]->(a1)
      CREATE (c2)-[:MODIFIED]->(a2)
      CREATE (c3)-[:MODIFIED]->(a3)
      CREATE (c3)-[:CONTAINS]->(a3)          // snapshot = latest revision
      // B.java — added in c1, present-but-unchanged at c3
      CREATE (b1:FileRevision {name:'B.java', hash:'b1', filePath:'src/B.java', `metrics.lineCount`:50.0})
      CREATE (c1)-[:ADDED]->(b1)
      CREATE (c3)-[:CONTAINS]->(b1)
      // C.java — in the c3 snapshot, NO change edge, NO lineCount (zero-fill + coalesce test)
      CREATE (cc:FileRevision {name:'C.java', hash:'cc', filePath:'src/C.java'})
      CREATE (c3)-[:CONTAINS]->(cc)
      // second landscape, SAME repo name — scoping guard (fable/13 finding 2)
      CREATE (l2:Landscape {tokenId:'t2'})-[:CONTAINS]->(r2:Repository {name:'repo1'})
      CREATE (r2)-[:CONTAINS]->(x:Commit {hash:'x', commitDate:5000})
      CREATE (eve:Contributor {gitUsername:'eve'})-[:AUTHORED]->(x)
      CREATE (xf:FileRevision {name:'X.java', hash:'xf', filePath:'src/X.java', `metrics.lineCount`:9.0})
      CREATE (x)-[:MODIFIED]->(xf)
      CREATE (x)-[:CONTAINS]->(xf)
      """;

  @Inject SocialMetricsRepository repo;
  @Inject SessionFactory sessionFactory;

  @BeforeEach
  void setup() {
    Session s = sessionFactory.openSession();
    resetDatabase(s); // same helper CommitRepositoryTest uses
    s.query(SEED, Map.of());
  }

  @Test
  void snapshotReturnsExactlyTheAnchorCommitsFiles() {
    var snaps = repo.getFileSnapshots(session(), "t1", "repo1", "c3");
    var byPath = snaps.stream().collect(toMap(FileSnapshot::path, s -> s));
    assertEquals(Set.of("src/A.java", "src/B.java", "src/C.java"), byPath.keySet());
    assertEquals(120.0, byPath.get("src/A.java").loc(), 1e-9); // latest revision, not a1/a2
    assertEquals(50.0, byPath.get("src/B.java").loc(), 1e-9);
  }

  @Test
  void snapshotCoalescesMissingLineCountToZero() {
    var byPath =
        repo.getFileSnapshots(session(), "t1", "repo1", "c3").stream()
            .collect(toMap(FileSnapshot::path, s -> s));
    assertEquals(0.0, byPath.get("src/C.java").loc(), 1e-9); // no metrics.lineCount
  }

  @Test
  void snapshotFileRevisionIdsAreRealNodeIds() {
    var snaps = repo.getFileSnapshots(session(), "t1", "repo1", "c3");
    assertTrue(snaps.stream().allMatch(s -> s.fileRevisionId() > 0));
    // (this id must equal the frontend Building.id — asserted end-to-end, not here)
  }

  @Test
  void snapshotIsScopedToLandscapeToken() {
    var paths =
        repo.getFileSnapshots(session(), "t1", "repo1", "c3").stream()
            .map(FileSnapshot::path)
            .toList();
    assertFalse(paths.contains("src/X.java")); // lives under token t2, same repo name
  }

  @Test
  void snapshotUnknownCommitReturnsEmpty() {
    assertTrue(repo.getFileSnapshots(session(), "t1", "repo1", "nope").isEmpty());
  }

  private Session session() {
    return sessionFactory.openSession();
  }

  @Test
  void baseAggregationCountsChangeCommitsPerFileAndAuthor() {
    var rows = repo.getBaseAggregation(session(), "t1", "repo1", 0L, Long.MAX_VALUE);
    // A.java touched by alice(c1,c2) and bob(c3); B.java by alice(c1); C.java never
    assertEquals(3, rows.size());

    var a = rows.stream().filter(r -> r.path().equals("src/A.java")).toList();
    assertEquals(2, a.size());
    assertEquals(3, a.stream().mapToLong(ContributorFileActivity::commits).sum()); // 2 + 1

    var alice = a.stream().filter(r -> "alice".equals(r.githubLogin())).findFirst().orElseThrow();
    assertEquals(2, alice.commits());
    assertEquals(2000, alice.lastDate()); // max(c1,c2)
  }

  @Test
  void baseAggregationIgnoresContainsOnlyFiles() { // the CONTAINS-vs-ADDED guard
    var rows = repo.getBaseAggregation(session(), "t1", "repo1", 0L, Long.MAX_VALUE);
    assertTrue(rows.stream().noneMatch(r -> r.path().equals("src/C.java")));
  }

  @Test
  void baseAggregationFiltersByCommitDateWindow() {
    var rows = repo.getBaseAggregation(session(), "t1", "repo1", 1500L, 3500L); // excludes c1@1000
    assertTrue(rows.stream().noneMatch(r -> r.path().equals("src/B.java"))); // B only in c1
    var a = rows.stream().filter(r -> r.path().equals("src/A.java")).toList();
    assertEquals(2, a.size()); // alice(c2), bob(c3)
    assertEquals(2, a.stream().mapToLong(ContributorFileActivity::commits).sum()); // 1 + 1
  }

  @Test
  void baseAggregationIsScopedToLandscapeToken() {
    var rows = repo.getBaseAggregation(session(), "t1", "repo1", 0L, Long.MAX_VALUE);
    assertTrue(rows.stream().noneMatch(r -> r.path().equals("src/X.java")));
  }
}
