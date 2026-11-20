package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Timestamp;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.SocialMetricDto;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.CommitService;
import net.explorviz.landscape.proto.ContributorData;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.proto.FileRename;
import net.explorviz.landscape.proto.StateDataRequest;
import net.explorviz.landscape.proto.StateDataService;
import net.explorviz.landscape.repository.SocialMetricsService;
import net.explorviz.landscape.util.MetricNormalizer.NormalizationOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/** Social metrics span several commits and have to follow a file across its renames. */
@QuarkusTest
class SocialMetricsRenameTest {

  private static final long GRPC_AWAIT_SECONDS = 30;
  private static final String LANDSCAPE_TOKEN = "social-rename-token";
  private static final String REPO_NAME = "social-rename-repo";
  private static final String BRANCH_NAME = "main";

  private static final String OLD_PATH = "src/Old.java";
  private static final String NEW_PATH = "src/pkg/New.java";
  private static final String OTHER_PATH = "src/Other.java";

  private static final String ALICE = "alice";
  private static final String BOB = "bob";

  @GrpcClient CommitService commitService;
  @GrpcClient StateDataService stateDataService;

  @Inject SessionFactory sessionFactory;
  @Inject SocialMetricsService socialMetricsService;

  private Session session;

  @BeforeEach
  void init() {
    session = sessionFactory.openSession();
    resetDatabase(session);

    stateDataService
        .getStateData(
            StateDataRequest.newBuilder()
                .setLandscapeToken(LANDSCAPE_TOKEN)
                .setRepositoryName(REPO_NAME)
                .setBranchName(BRANCH_NAME)
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
  }

  @Test
  void commitCountIncludesCommitsBeforeRename() {
    persistRenameHistory();

    final Map<String, SocialMetricDto> metrics = metricsAt("c4");

    assertEquals(Set.of(NEW_PATH, OTHER_PATH), metrics.keySet());
    assertEquals(4d, raw(metrics.get(NEW_PATH), "commitCount"));
    assertEquals(4d, raw(metrics.get(NEW_PATH), "commitActivity"));
    assertEquals(1d, raw(metrics.get(OTHER_PATH), "commitCount"));
  }

  @Test
  void contributorMetricsIncludeCommitsBeforeRename() {
    persistRenameHistory();

    // bob only changed the file before it was renamed, two thirds of the history ago
    final long bob = contributorId(BOB);
    assertEquals(1d, raw(metricsAt("c4", Set.of(bob)).get(NEW_PATH), "commitCount"));
    assertEquals(
        2d / 3, raw(metricsAt("c4", Set.of(bob)).get(NEW_PATH), "knowledgeStaleness"), 1e-9);
  }

  @Test
  void commitCountBeforeRenameIsUnchanged() {
    persistRenameHistory();

    final Map<String, SocialMetricDto> metrics = metricsAt("c2");

    assertEquals(Set.of(OLD_PATH, OTHER_PATH), metrics.keySet());
    assertEquals(2d, raw(metrics.get(OLD_PATH), "commitCount"));
  }

  @Test
  void fileCreatedAtThePreviousPathStartsWithoutHistory() {
    persistRenameHistory();
    persist("c5", "c4", 5, BOB, List.of(file("h5", OLD_PATH)), List.of(), List.of(), List.of());

    final Map<String, SocialMetricDto> metrics = metricsAt("c5");

    assertEquals(1d, raw(metrics.get(OLD_PATH), "commitCount"));
    assertEquals(4d, raw(metrics.get(NEW_PATH), "commitCount"));
  }

  @Test
  void commitCountRespectsTimeRangeAcrossRename() {
    persistRenameHistory();

    final Map<String, SocialMetricDto> metrics =
        metrics("c4", Set.of(), secondsToMillis(2), secondsToMillis(3));

    assertEquals(2d, raw(metrics.get(NEW_PATH), "commitCount"));
  }

  /** c1 adds, c2 modifies, c3 renames and c4 modifies the same file. */
  private void persistRenameHistory() {
    persist(
        "c1",
        null,
        1,
        ALICE,
        List.of(file("h1", OLD_PATH), file("o1", OTHER_PATH)),
        List.of(),
        List.of(),
        List.of());
    persist("c2", "c1", 2, BOB, List.of(), List.of(file("h2", OLD_PATH)), List.of(), List.of());
    final FileIdentifier oldFile = file("h2", OLD_PATH);
    final FileIdentifier newFile = file("h3", NEW_PATH);
    persist(
        "c3",
        "c2",
        3,
        ALICE,
        List.of(newFile),
        List.of(),
        List.of(oldFile),
        List.of(
            FileRename.newBuilder()
                .setOldFile(oldFile)
                .setNewFile(newFile)
                .setSimilarity(90)
                .build()));
    persist("c4", "c3", 4, ALICE, List.of(), List.of(file("h4", NEW_PATH)), List.of(), List.of());
  }

  private Map<String, SocialMetricDto> metricsAt(final String commit) {
    return metricsAt(commit, Set.of());
  }

  private Map<String, SocialMetricDto> metricsAt(final String commit, final Set<Long> authors) {
    return metrics(commit, authors, Long.MIN_VALUE, Long.MAX_VALUE);
  }

  private Map<String, SocialMetricDto> metrics(
      final String commit, final Set<Long> authors, final long from, final long to) {
    return socialMetricsService
        .calculateMetrics(
            session,
            LANDSCAPE_TOKEN,
            REPO_NAME,
            from,
            to,
            authors,
            commit,
            NormalizationOptions.DEFAULT)
        .stream()
        .collect(Collectors.toMap(SocialMetricDto::filePath, metric -> metric));
  }

  private static double raw(final SocialMetricDto metrics, final String metricId) {
    return metrics.metrics().get(metricId).raw();
  }

  private long contributorId(final String name) {
    return session.queryForObject(
        Long.class,
        "MATCH (c:Contributor {gitUsername: $name}) RETURN id(c)",
        Map.of("name", name));
  }

  private static long secondsToMillis(final long seconds) {
    return seconds * 1000;
  }

  private void persist(
      final String hash,
      final String parent,
      final long seconds,
      final String author,
      final List<FileIdentifier> added,
      final List<FileIdentifier> modified,
      final List<FileIdentifier> deleted,
      final List<FileRename> renamed) {
    final CommitData.Builder commit =
        CommitData.newBuilder()
            .setCommitId(hash)
            .setRepositoryName(REPO_NAME)
            .setBranchName(BRANCH_NAME)
            .setLandscapeToken(LANDSCAPE_TOKEN)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(seconds).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(seconds).build())
            .setAuthor(
                ContributorData.newBuilder()
                    .setLandscapeToken(LANDSCAPE_TOKEN)
                    .setRepositoryName(REPO_NAME)
                    .setGitUsername(author)
                    .setEmail(author + "@example.org")
                    .build())
            .addAllAddedFiles(added)
            .addAllModifiedFiles(modified)
            .addAllDeletedFiles(deleted)
            .addAllRenamedFiles(renamed);
    if (parent != null) {
      commit.setParentCommitId(parent);
      commit.addParentCommitIds(parent);
    }
    commitService
        .persistCommit(commit.build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
  }

  private static FileIdentifier file(final String hash, final String path) {
    return FileIdentifier.newBuilder().setFileHash(hash).setFilePath(path).build();
  }
}
