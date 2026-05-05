package net.explorviz.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import net.explorviz.persistence.ogm.AnnotationType;
import net.explorviz.persistence.ogm.Contributor;
import net.explorviz.persistence.ogm.Issue;
import net.explorviz.persistence.ogm.Landscape;
import net.explorviz.persistence.ogm.PullRequest;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.ogm.ResourceAnnotation;
import net.explorviz.persistence.ogm.ResourceState;
import net.explorviz.persistence.ogm.ResourceVersion;
import net.explorviz.persistence.repository.TrackableResourceRepository;
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
        trackableResourceRepository.getOrCreate(session, Issue.class, 100, repoName, tokenId);
    assertNotNull(newIssue);
    assertEquals(100, newIssue.getNumber());

    // Test PR creation
    PullRequest newPr =
        trackableResourceRepository.getOrCreate(session, PullRequest.class, 101, repoName, tokenId);
    assertNotNull(newPr);
    assertEquals(101, newPr.getNumber());
  }

  @Test
  void testFindByNumberWhenExists() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";

    // Setup graph
    Landscape landscape = new Landscape(tokenId);
    Repository repository = new Repository(repoName);

    Issue issue = new Issue();
    issue.setNumber(200);
    issue.setTitle("Test Issue");

    // We do a raw cypher save to link them since Repository doesn't have an addIssue method in our
    // model context,
    // or we can save them and manually attach relationships using cypher for testing the MATCH
    // query.
    session.save(landscape);
    session.save(repository);
    session.save(issue);

    session.query(
        """
        MATCH (l:Landscape {tokenId: $tokenId}), (r:Repository {name: $repoName}), (i:Issue {number: $number})
        CREATE (l)-[:CONTAINS]->(r)-[:CONTAINS]->(i)
        """,
        java.util.Map.of("tokenId", tokenId, "repoName", repoName, "number", 200));

    // Call findByNumber
    Optional<Issue> foundIssue =
        trackableResourceRepository.findByNumber(session, Issue.class, 200, repoName, tokenId);

    assertTrue(foundIssue.isPresent());
    assertEquals(200, foundIssue.get().getNumber());
    assertEquals("Test Issue", foundIssue.get().getTitle());
  }

  @Test
  void testFindByNumberWrongRepo() {
    final Session session = sessionFactory.openSession();

    Landscape landscape = new Landscape("token-1");
    Repository repository = new Repository("repo-1");
    Issue issue = new Issue();
    issue.setNumber(300);

    session.save(landscape);
    session.save(repository);
    session.save(issue);

    session.query(
        """
        MATCH (l:Landscape {tokenId: $tokenId}), (r:Repository {name: $repoName}), (i:Issue {number: $number})
        CREATE (l)-[:CONTAINS]->(r)-[:CONTAINS]->(i)
        """,
        java.util.Map.of("tokenId", "token-1", "repoName", "repo-1", "number", 300));

    // Call finding on wrong repo
    Optional<Issue> notFoundIssue =
        trackableResourceRepository.findByNumber(session, Issue.class, 300, "repo-2", "token-1");
    assertFalse(notFoundIssue.isPresent());
  }

  //  @Test
  //  void testAddEventToResource() {
  //
  //    final Session session = sessionFactory.openSession();
  //    final String tokenId = "token-1";
  //    final String repoName = "repo-1";
  //
  //    // Setup graph
  //    Landscape landscape = new Landscape(tokenId);
  //    Repository repository = new Repository(repoName);
  //
  //    Issue issue = new Issue();
  //    issue.setNumber(200);
  //    issue.setTitle("Test Issue");
  //
  //    // We do a raw cypher save to link them since Repository doesn't have an addIssue method in
  // our
  //    // model context,
  //    // or we can save them and manually attach relationships using cypher for testing the MATCH
  //    // query.
  //    session.save(landscape);
  //    session.save(repository);
  //    session.save(issue);
  //
  //    // TODO: Maybe the relationships should be created in the repository class already
  //    session.query(
  //        """
  //        MATCH (l:Landscape {tokenId: $tokenId}), (r:Repository {name: $repoName}), (i:Issue
  // {number: $number})
  //        CREATE (l)-[:CONTAINS]->(r)-[:CONTAINS]->(i)
  //        """,
  //        java.util.Map.of("tokenId", tokenId, "repoName", repoName, "number", 200));
  //
  //    CreatedEvent event =
  //        new CreatedEvent(Instant.now(), new Contributor("test"), "external-1", issue);
  //    trackableResourceRepository.addEventToResource(
  //        session, Issue.class, 200, "repo-1", "token-1", event);
  //
  //    session.save(event);
  //
  //    Optional<Issue> foundIssue =
  //        trackableResourceRepository.findByNumber(session, Issue.class, 200, repoName, tokenId);
  //    assertTrue(foundIssue.isPresent());
  //    Issue updatedIssue = foundIssue.get();
  //    System.out.println("Events: " + updatedIssue.getEvents());
  //    assertNotNull(updatedIssue.getEvents());
  //    assertTrue(updatedIssue.getEvents().stream().anyMatch(e -> e instanceof CreatedEvent));
  //  }

  @Test
  void testFindByContributor() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";
    Landscape landscape = new Landscape(tokenId);
    Repository repository = new Repository("repo-1");
    landscape.addRepository(repository);

    Contributor c1 = new Contributor("c1");
    Contributor c2 = new Contributor("c2");

    Issue i1 = new Issue();
    i1.setNumber(1);
    i1.setTitle("Test Issue 1");
    session.save(i1);

    Issue i2 = new Issue();
    i2.setNumber(2);
    i2.setTitle("Test Issue 2");
    session.save(i2);

    Issue i3 = new Issue();
    i3.setNumber(3);
    i3.setTitle("Test Issue 3");
    session.save(i3);

    repository.addIssue(i1);
    repository.addIssue(i2);
    repository.addIssue(i3);
    session.save(landscape);
    session.save(repository);

    ResourceVersion ir1 = new ResourceVersion();
    ResourceAnnotation ia1 =
        trackableResourceRepository.addAnnotationAndVersion(
            session,
            Issue.class,
            1,
            repoName,
            tokenId,
            "external-1",
            Instant.now(),
            AnnotationType.CREATE,
            c1,
            ir1);

    ResourceVersion ir2 = new ResourceVersion();
    ResourceAnnotation ia2 =
        trackableResourceRepository.addAnnotationAndVersion(
            session,
            Issue.class,
            2,
            repoName,
            tokenId,
            "external-2",
            Instant.now(),
            AnnotationType.CREATE,
            c1,
            ir2);

    ResourceVersion ir3 = new ResourceVersion();
    ResourceAnnotation ia3 =
        trackableResourceRepository.addAnnotationAndVersion(
            session,
            Issue.class,
            3,
            repoName,
            tokenId,
            "external-3",
            Instant.now(),
            AnnotationType.CREATE,
            c2,
            ir3);

    // issue number 3 gets edited again by contributor 2
    ResourceVersion ir4 = new ResourceVersion();
    ResourceAnnotation ia4 =
        trackableResourceRepository.addAnnotationAndVersion(
            session,
            Issue.class,
            3,
            repoName,
            tokenId,
            "external-4",
            Instant.now(),
            AnnotationType.LABEL,
            c2,
            ir4);
    ia4.setLabel("bug");

    Set<Issue> c1Issues =
        trackableResourceRepository.findAllByContributor(
            session, Issue.class, repoName, tokenId, c1);
    assertNotNull(c1Issues);
    assertEquals(2, c1Issues.size());

    Set<Issue> c2Issues =
        trackableResourceRepository.findAllByContributor(
            session, Issue.class, repoName, tokenId, c2);
    assertNotNull(c2Issues);
    assertEquals(1, c2Issues.size());
  }

  @Test
  void testAddAnnotationAndVersion() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";
    Landscape landscape = new Landscape(tokenId);
    Repository repository = new Repository(repoName);
    landscape.addRepository(repository);

    Contributor contributor = new Contributor("IssueCreator");
    Issue issue = new Issue();
    issue.setNumber(200);
    issue.setTitle("Test Issue");
    session.save(landscape);
    session.save(repository);
    session.save(issue);

    session.query(
        """
        MATCH (l:Landscape {tokenId: $tokenId}), (r:Repository {name: $repoName}), (i:Issue {number: $number})
        CREATE (l)-[:CONTAINS]->(r)-[:CONTAINS]->(i)
        """,
        java.util.Map.of("tokenId", tokenId, "repoName", repoName, "number", 200));

    // Create new Issue Version on creation with creation annotation
    Contributor creator = new Contributor("annotator");
    ResourceVersion creationVersion = new ResourceVersion();
    creationVersion.setCreationDate(Instant.now());
    creationVersion.setCreatedBy(creator);
    creationVersion.setTitle("Test issue");
    creationVersion.setState(ResourceState.OPEN);
    creationVersion.setExternalId("external-1");

    ResourceAnnotation creationAnnotation =
        trackableResourceRepository.addAnnotationAndVersion(
            session,
            Issue.class,
            200,
            repoName,
            tokenId,
            "external-1",
            Instant.now(),
            AnnotationType.CREATE,
            creator,
            creationVersion);
    assertNotNull(creationAnnotation);

    // find issue and test that versions are present
    Optional<Issue> foundIssue =
        trackableResourceRepository.findByNumber(session, Issue.class, 200, repoName, tokenId);
    assertTrue(foundIssue.isPresent());
    Issue updatedIssue = foundIssue.get();
    assertNotNull(updatedIssue.getVersions());
    assertEquals(1, updatedIssue.getVersions().size());

    // test getCurrentVersion
    ResourceVersion currentVersion = updatedIssue.getCurrentVersion();
    assertNotNull(currentVersion);
    assertEquals(creationVersion.getCreationDate(), currentVersion.getCreationDate());
    assertEquals(creationVersion.getCreatedBy(), creator);

    // Create another annotation
    Contributor closer = new Contributor("closer");
    ResourceVersion closedVersion = new ResourceVersion();

    closedVersion.setTitle(currentVersion.getTitle());
    closedVersion.setState(ResourceState.CLOSED);
    closedVersion.setExternalId(currentVersion.getExternalId());
    closedVersion.setCreationDate(Instant.now());
    closedVersion.setCreatedBy(closer);

    ResourceAnnotation closedAnnotation =
        trackableResourceRepository.addAnnotationAndVersion(
            session,
            Issue.class,
            200,
            repoName,
            tokenId,
            "external-1",
            Instant.now(),
            AnnotationType.CLOSE,
            closer,
            closedVersion);

    assertNotNull(closedAnnotation);
    assertNotNull(closedAnnotation.getUsedResource());
    assertEquals(ResourceState.OPEN, closedAnnotation.getUsedResource().getState());
    assertNotNull(closedAnnotation.getGeneratedResourceVersion());
    assertEquals(ResourceState.CLOSED, closedAnnotation.getGeneratedResourceVersion().getState());
    assertEquals(closer, closedAnnotation.getGeneratedResourceVersion().getCreatedBy());

    // get updated issue
    Optional<Issue> foundIssueAfterClose =
        trackableResourceRepository.findByNumber(session, Issue.class, 200, repoName, tokenId);
    assertTrue(foundIssueAfterClose.isPresent());
    Issue closedIssueAfterClose = foundIssueAfterClose.get();
    assertEquals(2, closedIssueAfterClose.getVersions().size());

    // test latest version again and walk chain to last
    ResourceVersion latestVersion = closedIssueAfterClose.getCurrentVersion();
    assertNotNull(latestVersion);
    assertEquals(ResourceState.CLOSED, latestVersion.getState());
    assertEquals(ResourceState.OPEN, latestVersion.getGeneratedBy().getUsedResource().getState());

    // test derivation
    assertNotNull(latestVersion.getDerivedFrom());
    assertEquals(ResourceState.OPEN, latestVersion.getDerivedFrom().getState());
  }

  @Test
  void testAddAnnotationEvent() {
    final Session session = sessionFactory.openSession();
    final String tokenId = "token-1";
    final String repoName = "repo-1";
    Landscape landscape = new Landscape(tokenId);
    Repository repository = new Repository(repoName);
    landscape.addRepository(repository);

    Issue issue = new Issue();
    issue.setNumber(300);
    issue.setTitle("Test Event Issue");
    repository.addIssue(issue);
    session.save(landscape);
    session.save(repository);
    session.save(issue);

    // Create new Issue Version on creation
    Contributor creator = new Contributor("annotator-event");

    ResourceVersion creationVersion = new ResourceVersion();
    creationVersion.setCreationDate(Instant.now());
    creationVersion.setTitle("Test event issue");
    creationVersion.setState(ResourceState.OPEN);
    creationVersion.setExternalId("external-event-1");

    ResourceAnnotation creationAnnotation = new ResourceAnnotation();
    creationAnnotation.setAnnotationType(AnnotationType.CREATE);
    creationAnnotation.setTimestamp(Instant.now());
    creationAnnotation.setAssociatedContributor(creator);
    creationAnnotation.setExternalId("external-event-annotation-1");

    ResourceAnnotation savedAnnotation =
        trackableResourceRepository.addAnnotationEvent(
            session, issue, creationAnnotation, creationVersion);

    assertNotNull(savedAnnotation);

    // find issue and test that versions are present
    Optional<Issue> foundIssue =
        trackableResourceRepository.findByNumber(session, Issue.class, 300, repoName, tokenId);
    assertTrue(foundIssue.isPresent());

    Issue updatedIssue = foundIssue.get();
    assertNotNull(updatedIssue.getVersions());
    assertEquals(1, updatedIssue.getVersions().size());

    // test getCurrentVersion
    ResourceVersion currentVersion = updatedIssue.getCurrentVersion();
    assertNotNull(currentVersion);
    assertEquals(creationVersion.getExternalId(), currentVersion.getExternalId());
    assertEquals(creator, currentVersion.getCreatedBy());

    // Add another annotation to close it and test derivedFrom
    Contributor closer = new Contributor("closer-event");

    ResourceVersion closedVersion = new ResourceVersion();
    closedVersion.setTitle(currentVersion.getTitle());
    closedVersion.setState(ResourceState.CLOSED);
    closedVersion.setExternalId("external-event-2");
    closedVersion.setCreationDate(Instant.now());

    ResourceAnnotation closedAnnotation = new ResourceAnnotation();
    closedAnnotation.setAnnotationType(AnnotationType.CLOSE);
    closedAnnotation.setTimestamp(Instant.now());
    closedAnnotation.setAssociatedContributor(closer);
    closedAnnotation.setExternalId("external-event-annotation-2");

    ResourceAnnotation savedClosedAnnotation =
        trackableResourceRepository.addAnnotationEvent(
            session, updatedIssue, closedAnnotation, closedVersion);

    assertNotNull(savedClosedAnnotation);
    assertNotNull(savedClosedAnnotation.getUsedResource());
    assertEquals(ResourceState.OPEN, savedClosedAnnotation.getUsedResource().getState());

    // get updated issue
    Optional<Issue> foundIssueAfterClose =
        trackableResourceRepository.findByNumber(session, Issue.class, 300, repoName, tokenId);
    assertTrue(foundIssueAfterClose.isPresent());
    Issue closedIssueAfterClose = foundIssueAfterClose.get();
    assertEquals(2, closedIssueAfterClose.getVersions().size());

    // test latest version and derivation
    ResourceVersion latestVersion = closedIssueAfterClose.getCurrentVersion();
    assertNotNull(latestVersion);
    assertEquals(ResourceState.CLOSED, latestVersion.getState());
    assertNotNull(latestVersion.getDerivedFrom());
    assertEquals(ResourceState.OPEN, latestVersion.getDerivedFrom().getState());
  }
}
