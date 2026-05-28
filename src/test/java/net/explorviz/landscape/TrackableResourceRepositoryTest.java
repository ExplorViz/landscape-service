package net.explorviz.landscape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.explorviz.landscape.ogm.AnnotationType;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.ogm.Issue;
import net.explorviz.landscape.ogm.Landscape;
import net.explorviz.landscape.ogm.PullRequest;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.ogm.ResourceAnnotation;
import net.explorviz.landscape.ogm.ResourceState;
import net.explorviz.landscape.ogm.ResourceVersion;
import net.explorviz.landscape.repository.TrackableResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class TrackableResourceRepositoryTest {

  @Inject TrackableResourceRepository trackableResourceRepository;

  @Inject SessionFactory sessionFactory;

  @BeforeEach
  void cleanup() {
    final Session session = sessionFactory.openSession();
    session.purgeDatabase();
  }

  @Test
  void testGetOrCreateCreatesNewWhenNotFound() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";

    // Test Issue creation
    Issue newIssue =
        trackableResourceRepository.getOrCreateTrackableResource(
            session, Issue.class, 100, repoName, tokenId);
    assertNotNull(newIssue);
    assertEquals(100, newIssue.getNumber());

    // Test PR creation
    PullRequest newPr =
        trackableResourceRepository.getOrCreateTrackableResource(
            session, PullRequest.class, 101, repoName, tokenId);
    assertNotNull(newPr);
    assertEquals(101, newPr.getNumber());
  }

  @Test
  void testFindByNumberWhenExists() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";

    setupGraph(session, tokenId, repoName);
    Issue issue = createIssue(session, 200, "Test Issue");

    session.query(
        "MATCH (r:Repository {name: $repoName}), (i:Issue {number: $number}) CREATE"
            + " (r)-[:CONTAINS]->(i)",
        java.util.Map.of("repoName", repoName, "number", 200));

    Optional<Issue> foundIssue =
        trackableResourceRepository.findByNumber(session, Issue.class, 200, repoName, tokenId);

    assertTrue(foundIssue.isPresent());
    assertEquals(200, foundIssue.get().getNumber());
    assertEquals("Test Issue", foundIssue.get().getTitle());
  }

  @Test
  void testFindByNumberWrongRepo() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";

    setupGraph(session, tokenId, repoName);
    createIssue(session, 300, "Test Issue");

    session.query(
        "MATCH (r:Repository {name: $repoName}), (i:Issue {number: $number}) CREATE"
            + " (r)-[:CONTAINS]->(i)",
        java.util.Map.of("repoName", repoName, "number", 300));

    Optional<Issue> notFoundIssue =
        trackableResourceRepository.findByNumber(session, Issue.class, 300, "repo-2", "token-1");
    assertFalse(notFoundIssue.isPresent());
  }

  private void setupGraph(Session session, String tokenId, String repoName) {
    Landscape landscape = new Landscape(tokenId);
    Repository repository = new Repository(repoName);
    session.save(landscape);
    session.save(repository);
    session.query(
        "MATCH (l:Landscape {tokenId: $tokenId}), (r:Repository {name: $repoName}) CREATE"
            + " (l)-[:CONTAINS]->(r)",
        java.util.Map.of("tokenId", tokenId, "repoName", repoName));
  }

  private Issue createIssue(Session session, int number, String title) {
    Issue issue = new Issue();
    issue.setNumber(number);
    issue.setTitle(title);
    session.save(issue);
    return issue;
  }

  @Test
  void testFindByContributor() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";
    setupGraph(session, tokenId, repoName);

    Contributor c1 = new Contributor("c1");
    Contributor c2 = new Contributor("c2");

    Issue i1 = createIssue(session, 1, "Issue 1");
    Issue i2 = createIssue(session, 2, "Issue 2");
    Issue i3 = createIssue(session, 3, "Issue 3");

    // Link issues to repo
    session.query(
        "MATCH (r:Repository {name: $repoName}), (i:Issue) WHERE i.number IN [1,2,3] CREATE"
            + " (r)-[:CONTAINS]->(i)",
        java.util.Map.of("repoName", repoName));

    ResourceAnnotation ra1 = createAnnotation(c1, "ext-1", AnnotationType.CREATE);
    trackableResourceRepository.addAnnotationEvent(
        session, i1, ra1, createVersion("v-1", ResourceState.OPEN));

    ResourceAnnotation ra2 = createAnnotation(c1, "ext-2", AnnotationType.CREATE);
    trackableResourceRepository.addAnnotationEvent(
        session, i2, ra2, createVersion("v-2", ResourceState.OPEN));

    ResourceAnnotation ra3 = createAnnotation(c2, "ext-3", AnnotationType.CREATE);
    trackableResourceRepository.addAnnotationEvent(
        session, i3, ra3, createVersion("v-3", ResourceState.OPEN));

    Set<Issue> c1Issues =
        trackableResourceRepository.findAllByContributor(
            session, Issue.class, repoName, tokenId, c1);
    assertEquals(2, c1Issues.size());

    Set<Issue> c2Issues =
        trackableResourceRepository.findAllByContributor(
            session, Issue.class, repoName, tokenId, c2);
    assertEquals(1, c2Issues.size());
  }

  @Test
  void shouldCreateFirstVersionOnInitialAnnotation() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";
    setupGraph(session, tokenId, repoName);
    Issue issue = createIssue(session, 300, "Issue 300");
    linkIssueToRepo(session, repoName, 300);

    Contributor creator = new Contributor("creator");
    ResourceAnnotation annotation = createAnnotation(creator, "ext-ann-1", AnnotationType.CREATE);
    ResourceVersion version = createVersion("ext-ver-1", ResourceState.OPEN);

    trackableResourceRepository.addAnnotationEvent(session, issue, annotation, version);

    Optional<Issue> found =
        trackableResourceRepository.findByNumber(session, Issue.class, 300, repoName, tokenId);
    assertTrue(found.isPresent());
    assertEquals(1, found.get().getVersions().size());
    assertEquals(ResourceState.OPEN, found.get().getCurrentVersion().getState());
  }

  @Test
  void shouldDeriveNewVersionWhenUpdatingExistingResource() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";
    setupGraph(session, tokenId, repoName);
    Issue issue = createIssue(session, 400, "Issue 400");
    linkIssueToRepo(session, repoName, 400);

    Contributor creator = new Contributor("creator");
    trackableResourceRepository.addAnnotationEvent(
        session,
        issue,
        createAnnotation(creator, "ann-1", AnnotationType.CREATE),
        createVersion("ver-1", ResourceState.OPEN));

    Contributor closer = new Contributor("closer");
    trackableResourceRepository.addAnnotationEvent(
        session,
        issue,
        createAnnotation(closer, "ann-2", AnnotationType.CLOSE),
        createVersion("ver-2", ResourceState.CLOSED));

    Optional<Issue> found =
        trackableResourceRepository.findByNumber(session, Issue.class, 400, repoName, tokenId);
    assertTrue(found.isPresent());
    Issue updatedIssue = found.get();
    assertEquals(2, updatedIssue.getVersions().size());

    ResourceVersion latest = updatedIssue.getCurrentVersion();
    assertEquals(ResourceState.CLOSED, latest.getState());
    assertNotNull(latest.getDerivedFrom());
    assertEquals(ResourceState.OPEN, latest.getDerivedFrom().getState());
  }

  @Test
  void shouldMaintainConsistentVersionChainWhenEventsArriveOutOfOrder() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-order";
    final String repoName = "repo-order";
    setupGraph(session, tokenId, repoName);
    Issue issue = createIssue(session, 500, "Issue 500");
    linkIssueToRepo(session, repoName, 500);

    Contributor tester = new Contributor("tester");

    // Persist Event A (10:00)
    Instant timeA = Instant.parse("2026-05-13T10:00:00Z");
    trackableResourceRepository.addAnnotationEvent(
        session,
        issue,
        createAnnotation(tester, "ann-A", AnnotationType.CREATE, timeA),
        createVersion("ver-A", ResourceState.OPEN, timeA));

    // Persist Event C (12:00) - Gap Append
    Instant timeC = Instant.parse("2026-05-13T12:00:00Z");
    trackableResourceRepository.addAnnotationEvent(
        session,
        issue,
        createAnnotation(tester, "ann-C", AnnotationType.CLOSE, timeC),
        createVersion("ver-C", ResourceState.CLOSED, timeC));

    // Persist Event B (11:00) - Middle Insertion
    Instant timeB = Instant.parse("2026-05-13T11:00:00Z");
    trackableResourceRepository.addAnnotationEvent(
        session,
        issue,
        createAnnotation(tester, "ann-B", AnnotationType.LABEL, timeB),
        createVersion("ver-B", ResourceState.OPEN, timeB));

    // Persist Event 0 (09:00) - Prepend
    Instant time0 = Instant.parse("2026-05-13T09:00:00Z");
    trackableResourceRepository.addAnnotationEvent(
        session,
        issue,
        createAnnotation(tester, "ann-0", AnnotationType.CREATE, time0),
        createVersion("ver-0", ResourceState.OPEN, time0));

    session.clear();

    // Verify Chain: 0 -> A -> B -> C
    Optional<Issue> found =
        trackableResourceRepository.findByNumber(session, Issue.class, 500, repoName, tokenId);
    Issue finalIssue = found.get();
    assertEquals(500, found.get().getNumber());
    assertEquals(4, finalIssue.getVersions().size());

    ResourceVersion vC = finalIssue.getCurrentVersion();
    assertEquals("ver-C", vC.getExternalId());

    ResourceVersion vB = vC.getDerivedFrom();
    assertNotNull(vB);
    assertEquals("ver-B", vB.getExternalId());

    ResourceVersion vA = vB.getDerivedFrom();
    assertNotNull(vA);
    assertEquals("ver-A", vA.getExternalId());

    ResourceVersion v0 = vA.getDerivedFrom();
    assertNotNull(v0);
    assertEquals("ver-0", v0.getExternalId());
    assertNull(v0.getDerivedFrom());

    // Verify Annotation Linkage for B -> C relink
    ResourceAnnotation annCNode =
        session.queryForObject(
            ResourceAnnotation.class,
            "MATCH (a:ResourceAnnotation {externalId: 'ann-C'}) RETURN a",
            Map.of());
    ResourceAnnotation annC = session.load(ResourceAnnotation.class, annCNode.getId(), 1);
    assertEquals("ver-B", annC.getUsedResource().getExternalId());
  }

  private void linkIssueToRepo(Session session, String repoName, int number) {
    session.query(
        "MATCH (r:Repository {name: $repoName}), (i:Issue {number: $number}) MERGE"
            + " (r)-[:CONTAINS]->(i)",
        java.util.Map.of("repoName", repoName, "number", number));
  }

  private ResourceAnnotation createAnnotation(
      Contributor c, String extId, AnnotationType type, Instant time) {
    ResourceAnnotation ra = new ResourceAnnotation();
    ra.setAnnotationType(type);
    ra.setAssociatedContributor(c);
    ra.setExternalId(extId);
    ra.setTimestamp(time);
    return ra;
  }

  private ResourceVersion createVersion(String extId, ResourceState state, Instant time) {
    ResourceVersion rv = new ResourceVersion();
    rv.setExternalId(extId);
    rv.setState(state);
    rv.setCreationDate(time);
    return rv;
  }

  private ResourceAnnotation createAnnotation(Contributor c, String extId, AnnotationType type) {
    return createAnnotation(c, extId, type, Instant.now());
  }

  private ResourceVersion createVersion(String extId, ResourceState state) {
    return createVersion(extId, state, Instant.now());
  }
}
