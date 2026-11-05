package net.explorviz.landscape.repository;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class StaticStructureQueryTest {

  private static final String TOKEN = "static-structure-test";
  private static final String REPO = "repo";

  @Inject SessionFactory sessionFactory;
  @Inject StructureRepository structureRepository;

  private Session session;

  @BeforeEach
  void setUp() {
    session = sessionFactory.openSession();
    resetDatabase(session);
    session.query(
        """
        CREATE (l:Landscape {tokenId: $token})
        CREATE (l)-[:CONTAINS]->(r:Repository {name: 'repo'})
        CREATE (r)-[:HAS_ROOT]->(root:Directory {name: 'repo'})
        CREATE (l)-[:CONTAINS]->(a:Application {name: 'repo'})-[:HAS_ROOT]->(root)
        CREATE (root)-[:CONTAINS]->(pkg:Directory {name: 'pkg'})
        CREATE (pkg)-[:CONTAINS]->(sub:Directory {name: 'sub'})
        CREATE (root)-[:CONTAINS]->(unused:Directory {name: 'unused'})

        CREATE (root)-[:CONTAINS]->(fa:FileRevision {
          name: 'A.java', filePath: 'A.java', hash: 'a', repoName: 'repo', language: 'JAVA',
          importNames: ['not', 'mapped'], `metrics.size`: 10.0})
        CREATE (pkg)-[:CONTAINS]->(fb1:FileRevision {
          name: 'B.java', filePath: 'pkg/B.java', hash: 'b1', repoName: 'repo'})
        CREATE (pkg)-[:CONTAINS]->(fb2:FileRevision {
          name: 'B.java', filePath: 'pkg/B.java', hash: 'b2', repoName: 'repo'})
        CREATE (sub)-[:CONTAINS]->(fc:FileRevision {
          name: 'C.java', filePath: 'pkg/sub/C.java', hash: 'c', repoName: 'repo'})
        CREATE (unused)-[:CONTAINS]->(fd:FileRevision {
          name: 'D.java', filePath: 'unused/D.java', hash: 'd', repoName: 'repo'})

        CREATE (r)-[:CONTAINS]->(c1:Commit {hash: 'c1', authorDate: 1000, commitDate: 1000})
        CREATE (r)-[:CONTAINS]->(c2:Commit {hash: 'c2', authorDate: 2000, commitDate: 2000})
        CREATE (r)-[:CONTAINS]->(c3:Commit {hash: 'c3', authorDate: 3000, commitDate: 3000})
        CREATE (r)-[:CONTAINS]->(main:Branch {name: 'main'})
        CREATE (c1)-[:BELONGS_TO]->(main), (c2)-[:BELONGS_TO]->(main), (c3)-[:BELONGS_TO]->(main)
        CREATE (c2)-[:HAS_FIRST_PARENT]->(c1), (c3)-[:HAS_FIRST_PARENT]->(c2)

        CREATE (c1)-[:CONTAINS]->(fa), (c1)-[:CONTAINS]->(fb1), (c1)-[:CONTAINS]->(fd)
        CREATE (c2)-[:CONTAINS]->(fa), (c2)-[:CONTAINS]->(fb2), (c2)-[:CONTAINS]->(fc)
        CREATE (c3)-[:CONTAINS]->(fa), (c3)-[:CONTAINS]->(fb2), (c3)-[:CONTAINS]->(fc)

        // Another repository of the same landscape, which must not leak into the results.
        CREATE (l)-[:CONTAINS]->(r2:Repository {name: 'other'})
        CREATE (r2)-[:HAS_ROOT]->(root2:Directory {name: 'other'})
        CREATE (l)-[:CONTAINS]->(a2:Application {name: 'other'})-[:HAS_ROOT]->(root2)
        CREATE (root2)-[:CONTAINS]->(fo:FileRevision {
          name: 'O.java', filePath: 'O.java', hash: 'o', repoName: 'other'})
        CREATE (r2)-[:CONTAINS]->(o1:Commit {hash: 'c1', authorDate: 1000, commitDate: 1000})
        CREATE (o1)-[:CONTAINS]->(fo)
        """,
        Map.of("token", TOKEN));
  }

  private static Set<String> fqns(final FlatLandscapeDto landscape) {
    return landscape.buildings().values().stream()
        .map(building -> building.flatBaseModel().fqn())
        .collect(Collectors.toSet());
  }

  private static BuildingDto building(final FlatLandscapeDto landscape, final String fqn) {
    return landscape.buildings().values().stream()
        .filter(building -> fqn.equals(building.flatBaseModel().fqn()))
        .findFirst()
        .orElseThrow();
  }

  private static DistrictDto district(final FlatLandscapeDto landscape, final String fqn) {
    return landscape.districts().values().stream()
        .filter(district -> fqn.equals(district.flatBaseModel().fqn()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void singleCommitContainsOnlyItsFilesAndTheirDirectories() {
    final FlatLandscapeDto landscape =
        structureRepository.fetchFlatLandscapeForStaticData(
            session, new StructureRepository.StaticDataRequest(TOKEN, REPO, "c1"));

    assertEquals(1, landscape.cities().size());
    assertEquals(Set.of("A.java", "pkg/B.java", "unused/D.java"), fqns(landscape));
    assertEquals(
        Set.of("pkg", "unused"),
        landscape.districts().values().stream()
            .map(district -> district.flatBaseModel().fqn())
            .collect(Collectors.toSet()));

    final var city = landscape.cities().values().iterator().next();
    final BuildingDto a = building(landscape, "A.java");
    final BuildingDto b = building(landscape, "pkg/B.java");
    final DistrictDto pkg = district(landscape, "pkg");

    assertEquals("repo", city.flatBaseModel().name());
    assertEquals(List.of(a.flatBaseModel().id()), city.buildingIds());
    assertEquals(3, city.allContainedBuildingIds().size());
    assertEquals(2, city.allContainedDistrictIds().size());
    assertEquals(null, a.parentDistrictId());
    assertEquals(pkg.flatBaseModel().id(), b.parentDistrictId());
    assertEquals(city.flatBaseModel().id(), pkg.parentCityId());
    assertEquals(List.of(b.flatBaseModel().id()), pkg.buildingIds());
    assertEquals("b1", b.fileHash());
    assertEquals("JAVA", a.language());
    assertEquals("10.0", a.metrics().get("size").current());
  }

  @Test
  void nestedDirectoriesAreLinkedToTheirParents() {
    final FlatLandscapeDto landscape =
        structureRepository.fetchFlatLandscapeForStaticData(
            session, new StructureRepository.StaticDataRequest(TOKEN, REPO, "c2"));

    assertEquals(Set.of("A.java", "pkg/B.java", "pkg/sub/C.java"), fqns(landscape));
    final DistrictDto pkg = district(landscape, "pkg");
    final DistrictDto sub = district(landscape, "pkg/sub");

    assertEquals(List.of(sub.flatBaseModel().id()), pkg.districtIds());
    assertEquals(pkg.flatBaseModel().id(), sub.parentDistrictId());
    assertEquals("b2", building(landscape, "pkg/B.java").fileHash());
    assertEquals(
        sub.flatBaseModel().id(), building(landscape, "pkg/sub/C.java").parentDistrictId());
  }

  @Test
  void combinedLandscapeComparesBothCommits() {
    final FlatLandscapeDto landscape =
        structureRepository.fetchCombinedFlatLandscape(
            session, new StructureRepository.CombinedStaticDataRequest(TOKEN, REPO, "c1", "c2"));

    assertEquals(
        Set.of("A.java", "pkg/B.java", "pkg/sub/C.java", "unused/D.java"), fqns(landscape));
    assertEquals(
        CommitComparison.UNCHANGED,
        building(landscape, "A.java").flatBaseModel().commitComparison());
    assertEquals(
        CommitComparison.MODIFIED,
        building(landscape, "pkg/B.java").flatBaseModel().commitComparison());
    assertEquals(
        CommitComparison.ADDED,
        building(landscape, "pkg/sub/C.java").flatBaseModel().commitComparison());
    assertEquals(
        CommitComparison.REMOVED,
        building(landscape, "unused/D.java").flatBaseModel().commitComparison());
  }

  @Test
  void unknownCommitYieldsEmptyLandscape() {
    final FlatLandscapeDto landscape =
        structureRepository.fetchFlatLandscapeForStaticData(
            session, new StructureRepository.StaticDataRequest(TOKEN, REPO, "missing"));

    assertTrue(landscape.cities().isEmpty());
    assertTrue(landscape.districts().isEmpty());
    assertTrue(landscape.buildings().isEmpty());
  }

  @Test
  void repositoriesWithoutRootDirectoryUseTheApplicationsOfTheLandscape() {
    session.query("MATCH (:Repository {name: 'repo'})-[r:HAS_ROOT]->() DELETE r", Map.of());

    final FlatLandscapeDto landscape =
        structureRepository.fetchFlatLandscapeForStaticData(
            session, new StructureRepository.StaticDataRequest(TOKEN, REPO, "c1"));

    assertEquals(Set.of("A.java", "pkg/B.java", "unused/D.java"), fqns(landscape));
  }

  @Test
  void animationWindowReadsEachFrameAgainstItsPredecessor() {
    final var window =
        structureRepository.fetchAnimationWindow(session, TOKEN, REPO, 0, -1, 1, "commit", 1000);

    assertEquals(3, window.totalCount());
    assertEquals(
        List.of("c1", "c2", "c3"), window.frames().stream().map(f -> f.commitHash()).toList());

    final FlatLandscapeDto first = window.frames().get(0).landscape();
    assertEquals(Set.of("A.java", "pkg/B.java", "unused/D.java"), fqns(first));
    assertEquals(
        CommitComparison.ADDED, building(first, "pkg/B.java").flatBaseModel().commitComparison());

    final FlatLandscapeDto second = window.frames().get(1).landscape();
    assertEquals(Set.of("A.java", "pkg/B.java", "pkg/sub/C.java", "unused/D.java"), fqns(second));
    assertEquals(
        CommitComparison.MODIFIED,
        building(second, "pkg/B.java").flatBaseModel().commitComparison());
    assertEquals(
        CommitComparison.REMOVED,
        building(second, "unused/D.java").flatBaseModel().commitComparison());

    final FlatLandscapeDto third = window.frames().get(2).landscape();
    assertEquals(Set.of("A.java", "pkg/B.java", "pkg/sub/C.java"), fqns(third));
    assertEquals(
        CommitComparison.UNCHANGED,
        building(third, "pkg/B.java").flatBaseModel().commitComparison());
  }
}
