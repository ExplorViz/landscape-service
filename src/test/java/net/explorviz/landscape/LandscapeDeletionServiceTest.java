package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.assertNodeCounts;
import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.messaging.TelemetryConsumer;
import net.explorviz.landscape.messaging.service.LandscapeDeletionService;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.ogm.Directory;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.ogm.Landscape;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.ContributorData;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.repository.ContributorRepository;
import net.explorviz.landscape.util.ExpectedCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class LandscapeDeletionServiceTest {

  private static final String TOKEN_TO_DELETE = "delete-me";
  private static final String TOKEN_TO_KEEP = "keep-me";

  @Inject LandscapeDeletionService landscapeDeletionService;

  @Inject TelemetryConsumer telemetryConsumer;

  @Inject ContributorRepository contributorRepository;

  @Inject SessionFactory sessionFactory;

  private Session session;

  @BeforeEach
  void setUp() {
    session = sessionFactory.openSession();
    resetDatabase(session);
  }

  @Test
  void deleteLandscapeDataRemovesRuntimeData() {
    persistRuntimeCodeEntity(TOKEN_TO_DELETE);

    assertTrue(landscapeExists(TOKEN_TO_DELETE));

    landscapeDeletionService.deleteLandscapeData(TOKEN_TO_DELETE);

    assertFalse(landscapeExists(TOKEN_TO_DELETE));
    assertNodeCounts(session, ExpectedCounts.builder().build());
  }

  @Test
  void deleteLandscapeDataRemovesStaticData() {
    persistStaticLandscape(TOKEN_TO_DELETE);

    assertTrue(landscapeExists(TOKEN_TO_DELETE));
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .commits(1)
            .files(1)
            .applications(1)
            .directories(4)
            .functions(1)
            .build());

    landscapeDeletionService.deleteLandscapeData(TOKEN_TO_DELETE);

    assertFalse(landscapeExists(TOKEN_TO_DELETE));
    assertNodeCounts(session, ExpectedCounts.builder().build());
  }

  @Test
  void deleteLandscapeDataDoesNotAffectOtherLandscapes() {
    persistRuntimeCodeEntity(TOKEN_TO_DELETE);
    persistStaticLandscape(TOKEN_TO_KEEP);

    landscapeDeletionService.deleteLandscapeData(TOKEN_TO_DELETE);

    assertFalse(landscapeExists(TOKEN_TO_DELETE));
    assertTrue(landscapeExists(TOKEN_TO_KEEP));
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .commits(1)
            .files(1)
            .applications(1)
            .directories(4)
            .functions(1)
            .build());
  }

  @Test
  void deleteLandscapeDataIsIdempotentForMissingLandscape() {
    landscapeDeletionService.deleteLandscapeData("non-existent-token");
    assertNodeCounts(session, ExpectedCounts.builder().build());
  }

  @Test
  void deleteLandscapeDataRemovesOrphanedContributors() {
    persistStaticLandscapeWithContributor(TOKEN_TO_DELETE, "alice@test.com");

    assertTrue(contributorExists("alice@test.com"));

    landscapeDeletionService.deleteLandscapeData(TOKEN_TO_DELETE);

    assertFalse(contributorExists("alice@test.com"));
    assertNodeCounts(session, ExpectedCounts.builder().build());
  }

  @Test
  void deleteLandscapeDataPreservesSharedContributors() {
    persistStaticLandscape(TOKEN_TO_DELETE);
    persistStaticLandscape(TOKEN_TO_KEEP);

    final ContributorData contributorData =
        ContributorData.newBuilder().setGitUsername("shared").setEmail("shared@test.com").build();
    final Contributor contributor =
        contributorRepository.getOrCreateContributor(session, contributorData);
    linkContributorToLandscape(TOKEN_TO_DELETE, contributor);
    linkContributorToLandscape(TOKEN_TO_KEEP, contributor);
    session.save(contributor);

    landscapeDeletionService.deleteLandscapeData(TOKEN_TO_DELETE);

    assertFalse(landscapeExists(TOKEN_TO_DELETE));
    assertTrue(landscapeExists(TOKEN_TO_KEEP));
    assertTrue(contributorExists("shared@test.com"));
  }

  private void persistRuntimeCodeEntity(final String landscapeToken) {
    final List<String> dirNames = List.of("net", "explorviz", "myApp");
    final List<String> filePath =
        ImmutableList.<String>builder().addAll(dirNames).add("MyClass.java").build();

    final TelemetryEntity entity =
        TelemetryEntity.newBuilder()
            .setLandscapeTokenId(landscapeToken)
            .setCodeDescriptor(
                CodeDescriptor.newBuilder()
                    .setApplicationName("myApp")
                    .setFilePath(String.join("/", filePath))
                    .setFunctionName("myMethod"))
            .build();

    telemetryConsumer.consume(entity.toByteArray());
  }

  private void persistStaticLandscape(final String landscapeToken) {
    final String repoName = "myrepo";
    final String branchName = "main";
    final String commitHash = "commit1";
    final List<String> dirNames = List.of("net", "explorviz", "myApp");
    final String fileName = "MyClass.java";

    final Branch branch = new Branch(branchName);
    final Repository repository = new Repository(repoName);
    repository.addBranch(branch);
    final Landscape landscape = new Landscape(landscapeToken);
    landscape.addRepository(repository);
    final Application application = new Application("myApp");
    application.setRootDirectory(new Directory(repoName));
    landscape.addApplication(application);

    Directory currentDir = application.getRootDirectory();
    repository.setRootDirectory(currentDir);
    for (final String dirName : dirNames) {
      final Directory newDir = new Directory(dirName);
      currentDir.addSubdirectory(newDir);
      currentDir = newDir;
    }

    final FileRevision file = new FileRevision(fileName);
    currentDir.addFileRevision(file);
    file.addFunction(new Function("myMethod"));
    file.setHash("1");

    final Commit commit = new Commit(commitHash);
    repository.addCommit(commit);
    commit.addFileRevision(file);
    commit.setBranch(branch);
    landscape.addRepository(repository);

    session.save(List.of(landscape, application));
  }

  private void persistStaticLandscapeWithContributor(
      final String landscapeToken, final String contributorEmail) {
    persistStaticLandscape(landscapeToken);

    final ContributorData contributorData =
        ContributorData.newBuilder().setGitUsername("alice").setEmail(contributorEmail).build();
    final Contributor contributor =
        contributorRepository.getOrCreateContributor(session, contributorData);
    linkContributorToLandscape(landscapeToken, contributor);
    session.save(contributor);
  }

  private void linkContributorToLandscape(
      final String landscapeToken, final Contributor contributor) {
    final Commit commit =
        session.queryForObject(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository)-[:CONTAINS]->(c:Commit)
            RETURN c
            LIMIT 1
            """,
            Map.of("tokenId", landscapeToken));

    contributor.addCommit(commit);
    commit.setAuthor(contributor);
    session.save(commit);
  }

  private boolean contributorExists(final String email) {
    return Boolean.TRUE.equals(
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Contributor {email: $email})
            } AS exists
            """,
            Map.of("email", email)));
  }

  private boolean landscapeExists(final String landscapeToken) {
    return Boolean.TRUE.equals(
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Landscape {tokenId: $tokenId})
            } AS exists
            """,
            Map.of("tokenId", landscapeToken)));
  }
}
