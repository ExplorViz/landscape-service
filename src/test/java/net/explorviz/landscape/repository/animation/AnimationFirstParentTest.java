package net.explorviz.landscape.repository;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.landscape.AnimationSkeletonDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class AnimationFirstParentTest {

  @Inject SessionFactory sessionFactory;
  @Inject StructureRepository structureRepository;

  private Session session;

  @BeforeEach
  void setUp() {
    session = sessionFactory.openSession();
    resetDatabase(session);
    session.query(
        """
        CREATE (l:Landscape {tokenId: 'animation-test'})-[:CONTAINS]->(r:Repository {name: 'repo'})
        CREATE (r)-[:CONTAINS]->(main:Branch {name: 'main'})
        CREATE (r)-[:CONTAINS]->(feature:Branch {name: 'feature'})
        CREATE (r)-[:CONTAINS]->(base:Commit {hash: 'base', authorDate: 1000, commitDate: 1000})
        CREATE (r)-[:CONTAINS]->(side:Commit {hash: 'side', authorDate: 2000, commitDate: 2000})
        CREATE (r)-[:CONTAINS]->(tip:Commit {hash: 'tip', authorDate: 3000, commitDate: 3000})
        CREATE (r)-[:CONTAINS]->(merge:Commit {hash: 'merge', authorDate: 4000, commitDate: 4000})
        CREATE (r)-[:CONTAINS]->(other:Commit {hash: 'feature', authorDate: 5000, commitDate: 5000})
        CREATE (base)-[:BELONGS_TO]->(main), (side)-[:BELONGS_TO]->(main),
          (tip)-[:BELONGS_TO]->(main), (merge)-[:BELONGS_TO]->(main),
          (other)-[:BELONGS_TO]->(feature)
        CREATE (side)-[:HAS_FIRST_PARENT]->(base), (tip)-[:HAS_FIRST_PARENT]->(base),
          (merge)-[:HAS_FIRST_PARENT]->(tip), (merge)-[:HAS_MISC_PARENT]->(side),
          (other)-[:HAS_FIRST_PARENT]->(base)
        CREATE (l)-[:CONTAINS]->(a:Application {name: 'app'})
          -[:HAS_ROOT]->(root:Directory {name: 'src'})
        CREATE (root)-[:CONTAINS]->(f:FileRevision {
          name: 'A.java', filePath: 'A.java', hash: 'a', repoName: 'repo'})
        CREATE (root)-[:CONTAINS]->(s:FileRevision {
          name: 'Side.java', filePath: 'Side.java', hash: 'side', repoName: 'repo'})
        CREATE (base)-[:CONTAINS]->(f), (tip)-[:CONTAINS]->(f),
          (merge)-[:CONTAINS]->(f), (other)-[:CONTAINS]->(f), (side)-[:CONTAINS]->(s)
        """,
        Map.of());
  }

  @Test
  void skeletonUsesFirstParentHistoryOfEachBranch() {
    final AnimationSkeletonDto skeleton =
        structureRepository.fetchAnimationSkeleton(session, "animation-test", "repo", 0, 0);

    assertEquals(List.of("base", "tip", "merge", "feature"), skeleton.orderedCommitHashes());
    assertEquals(List.of(1000L, 3000L, 4000L, 5000L), skeleton.orderedCommitTimestamps());
    assertEquals(Map.of("A.java", 0), skeleton.fqnToFirstOrdinal());
    assertEquals(1, skeleton.landscape().buildings().size());
    assertFalse(
        skeleton.landscape().buildings().values().stream()
            .anyMatch(building -> "Side.java".equals(building.flatBaseModel().fqn())));
  }

  @Test
  void rangeDoesNotTurnMergedSideCommitIntoBranchHead() {
    final AnimationSkeletonDto skeleton =
        structureRepository.fetchAnimationSkeleton(session, "animation-test", "repo", 2000, 3000);

    assertEquals(List.of("tip"), skeleton.orderedCommitHashes());
    assertEquals(Map.of("A.java", 0), skeleton.fqnToFirstOrdinal());
    assertEquals(1, skeleton.landscape().buildings().size());
  }

  @Test
  void skeletonChoosesOneRevisionPerPathAndPreservesParentLinks() {
    session.query(
        """
        MATCH (root:Directory {name: 'src'}), (base:Commit {hash: 'base'}),
          (tip:Commit {hash: 'tip'}), (merge:Commit {hash: 'merge'})
        CREATE (root)-[:CONTAINS]->(pkg:Directory {name: 'pkg'})
        CREATE (pkg)-[:CONTAINS]->(old:FileRevision {
          name: 'B.java', filePath: 'pkg/B.java', hash: 'old', repoName: 'repo'})
        CREATE (pkg)-[:CONTAINS]->(latest:FileRevision {
          name: 'B.java', filePath: 'pkg/B.java', hash: 'latest', repoName: 'repo', language: 'java'})
        CREATE (base)-[:CONTAINS]->(old), (tip)-[:CONTAINS]->(latest),
          (merge)-[:CONTAINS]->(latest)
        """,
        Map.of());

    final AnimationSkeletonDto skeleton =
        structureRepository.fetchAnimationSkeleton(session, "animation-test", "repo", 0, 0);
    final var landscape = skeleton.landscape();
    final var city = landscape.cities().values().iterator().next();
    final var district = landscape.districts().values().iterator().next();
    final var building =
        landscape.buildings().values().stream()
            .filter(b -> "pkg/B.java".equals(b.flatBaseModel().fqn()))
            .findFirst()
            .orElseThrow();

    assertEquals(2, landscape.buildings().size());
    assertEquals(1, landscape.districts().size());
    assertEquals("latest", building.fileHash());
    assertEquals("java", building.language());
    assertEquals("pkg", district.flatBaseModel().fqn());
    assertEquals(district.flatBaseModel().id(), building.parentDistrictId());
    assertEquals(List.of(building.flatBaseModel().id()), district.buildingIds());
    assertEquals(List.of(district.flatBaseModel().id()), city.districtIds());
    assertEquals(1, city.buildingIds().size());
    assertEquals(2, city.allContainedBuildingIds().size());
    assertEquals(Map.of("A.java", 0, "pkg/B.java", 0), skeleton.fqnToFirstOrdinal());
  }

  @Test
  void firstAppearanceUsesCommitOrdinalWhenDatesAreEqual() {
    session.query(
        """
        MATCH (tip:Commit {hash: 'tip'}), (root:Directory {name: 'src'})
        SET tip.authorDate = 1000
        CREATE (root)-[:CONTAINS]->(f:FileRevision {
          name: 'Added.java', filePath: 'Added.java', hash: 'added', repoName: 'repo'})
        CREATE (tip)-[:CONTAINS]->(f)
        """,
        Map.of());

    final AnimationSkeletonDto skeleton =
        structureRepository.fetchAnimationSkeleton(session, "animation-test", "repo", 0, 0);

    assertEquals(List.of("base", "tip", "merge", "feature"), skeleton.orderedCommitHashes());
    assertEquals(Map.of("A.java", 0, "Added.java", 1), skeleton.fqnToFirstOrdinal());
  }

  @Test
  void emptyRangeReturnsEmptySkeleton() {
    final AnimationSkeletonDto skeleton =
        structureRepository.fetchAnimationSkeleton(session, "animation-test", "repo", 6000, 7000);

    assertEquals(List.of(), skeleton.orderedCommitHashes());
    assertEquals(Map.of(), skeleton.fqnToFirstOrdinal());
    assertEquals(Map.of(), skeleton.landscape().cities());
    assertEquals(Map.of(), skeleton.landscape().districts());
    assertEquals(Map.of(), skeleton.landscape().buildings());
  }

  @Test
  void windowCountsAndBucketsOnlyFirstParentCommits() {
    final var window =
        structureRepository.fetchAnimationWindow(
            session, "animation-test", "repo", 0, -1, 2, "commit", 1000);

    assertEquals(2, window.totalCount());
    assertEquals(
        List.of("tip", "feature"), window.frames().stream().map(f -> f.commitHash()).toList());
  }

  @Test
  void deltaRangeCountsOnlyFirstParentCommits() {
    final var window =
        structureRepository.fetchAnimationDeltaWindow(
            session, "animation-test", "repo", 0, -1, 1, "commit", 1000, 1, 2000, 4000);

    assertEquals(2, window.totalCount());
    assertEquals(
        List.of("tip", "merge"), window.frames().stream().map(f -> f.commitHash()).toList());
    assertEquals(List.of(1, 1), window.frames().stream().map(f -> f.commitCount()).toList());
  }
}
