package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.landscape.FileHistoryDto;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.CommitService;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.proto.FileRename;
import net.explorviz.landscape.proto.StateDataRequest;
import net.explorviz.landscape.proto.StateDataService;
import net.explorviz.landscape.repository.CommitFileRevisionCache;
import net.explorviz.landscape.repository.FileHistoryRepository;
import net.explorviz.landscape.repository.FileRevisionIdCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class CommitRenameTest {

  private static final long GRPC_AWAIT_SECONDS = 5;
  private static final String LANDSCAPE_TOKEN = "rename-token";
  private static final String REPO_NAME = "rename-repo";
  private static final String BRANCH_NAME = "main";

  private static final String OLD_PATH = "src/Old.java";
  private static final String NEW_PATH = "src/pkg/New.java";

  @GrpcClient CommitService commitService;
  @GrpcClient StateDataService stateDataService;

  @Inject SessionFactory sessionFactory;
  @Inject FileHistoryRepository fileHistoryRepository;
  @Inject FileRevisionIdCache fileRevisionIdCache;
  @Inject CommitFileRevisionCache commitFileRevisionCache;

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
  void linksRenamedRevisionToItsPredecessor() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h2", NEW_PATH), 90);

    final List<Map<String, Object>> links = renameLinks();

    assertEquals(1, links.size());
    assertEquals(NEW_PATH, links.get(0).get("newPath"));
    assertEquals("h2", links.get(0).get("newHash"));
    assertEquals(OLD_PATH, links.get(0).get("oldPath"));
    assertEquals("h1", links.get(0).get("oldHash"));
    assertEquals("c2", links.get(0).get("commitHash"));
    assertEquals(90L, ((Number) links.get(0).get("similarity")).longValue());
  }

  @Test
  void renamedFileIsOnlyContainedUnderItsNewPath() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h1", NEW_PATH), 100);

    assertTrue(commitContainsPath("c2", NEW_PATH));
    assertFalse(commitContainsPath("c2", OLD_PATH));
    assertTrue(commitContainsPath("c1", OLD_PATH));
  }

  @Test
  void resolvesPredecessorFromParentCommitWhenCachesAreCold() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    fileRevisionIdCache.clear();
    commitFileRevisionCache.clear();

    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h1", NEW_PATH), 100);

    assertEquals(1, renameLinks().size());
  }

  @Test
  void skipsRenameWithUnknownPredecessorWithoutFailingTheCommit() {
    persistAdded("c1", null, 1, file("h1", "src/Other.java"));

    persistRename("c2", "c1", 2, file("unknown", OLD_PATH), file("h2", NEW_PATH), 80);

    assertTrue(renameLinks().isEmpty());
    assertTrue(commitContainsPath("c2", NEW_PATH));
    assertTrue(commitContainsPath("c2", "src/Other.java"));
  }

  @Test
  void historyFollowsFileAcrossRename() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persist("c2", "c1", 2, List.of(), List.of(file("h2", OLD_PATH)), List.of(), List.of());
    persistRename("c3", "c2", 3, file("h2", OLD_PATH), file("h3", NEW_PATH), 85);
    persist("c4", "c3", 4, List.of(), List.of(file("h4", NEW_PATH)), List.of(), List.of());

    final List<FileHistoryDto> history =
        fileHistoryRepository.fetchFileHistory(session, idOf("h4"));

    assertEquals(
        List.of("c1:ADDED", "c2:MODIFIED", "c3:RENAMED", "c4:MODIFIED"), describe(history));
    final FileHistoryDto rename = history.get(2);
    assertEquals(OLD_PATH, rename.renamedFrom());
    assertNull(history.get(0).renamedFrom());
    assertEquals(List.of(OLD_PATH, OLD_PATH, NEW_PATH, NEW_PATH), paths(history));
  }

  @Test
  void historyFollowsFileAcrossSeveralRenamesAndModifications() {
    final String thirdPath = "lib/Third.java";
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h1", NEW_PATH), 100);
    persist("c3", "c2", 3, List.of(), List.of(file("h2", NEW_PATH)), List.of(), List.of());
    persistRename("c4", "c3", 4, file("h2", NEW_PATH), file("h2", thirdPath), 100);

    final List<FileHistoryDto> history = historyOf("h2", thirdPath);

    assertEquals(List.of("c1:ADDED", "c2:RENAMED", "c3:MODIFIED", "c4:RENAMED"), describe(history));
    assertEquals(List.of(OLD_PATH, NEW_PATH, NEW_PATH, thirdPath), paths(history));
    assertEquals(OLD_PATH, history.get(1).renamedFrom());
    assertEquals(NEW_PATH, history.get(3).renamedFrom());
    assertEquals(history, historyOf("h1", OLD_PATH));
    assertEquals(history, historyOf("h2", NEW_PATH));
  }

  @Test
  void historyRequestedAtOldPathAlsoCoversChangesAfterRename() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h1", NEW_PATH), 100);
    persist("c3", "c2", 3, List.of(), List.of(file("h2", NEW_PATH)), List.of(), List.of());

    final List<FileHistoryDto> history = historyOf("h1", OLD_PATH);

    assertEquals(List.of("c1:ADDED", "c2:RENAMED", "c3:MODIFIED"), describe(history));
    assertEquals(List.of(OLD_PATH, NEW_PATH, NEW_PATH), paths(history));
    assertEquals(OLD_PATH, history.get(1).renamedFrom());
    assertEquals(history, historyOf("h2", NEW_PATH));
  }

  @Test
  void historyOfPureRenameStartsAtTheOriginalAddition() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h1", NEW_PATH), 100);

    assertEquals(List.of("c1:ADDED", "c2:RENAMED"), describe(historyOf("h1", NEW_PATH)));
  }

  @Test
  void historyTerminatesForFileRenamedBackAndForth() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persistRename("c2", "c1", 2, file("h1", OLD_PATH), file("h1", NEW_PATH), 100);
    persistRename("c3", "c2", 3, file("h1", NEW_PATH), file("h1", OLD_PATH), 100);

    final List<FileHistoryDto> history = historyOf("h1", OLD_PATH);

    assertEquals(List.of("c1:ADDED", "c2:RENAMED", "c3:RENAMED"), describe(history));
    assertEquals(OLD_PATH, history.get(1).renamedFrom());
    assertEquals(NEW_PATH, history.get(2).renamedFrom());
  }

  @Test
  void historyWithoutRenamesIsUnchanged() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persist("c2", "c1", 2, List.of(), List.of(file("h2", OLD_PATH)), List.of(), List.of());

    final List<FileHistoryDto> history = historyOf("h2", OLD_PATH);

    assertEquals(List.of("c1:ADDED", "c2:MODIFIED"), describe(history));
    history.forEach(entry -> assertNull(entry.renamedFrom()));
  }

  @Test
  void historyMarksMergeCommits() {
    persistAdded("base", null, 1, file("h1", OLD_PATH));
    persist("feature", "base", 2, List.of(), List.of(file("h2", OLD_PATH)), List.of(), List.of());
    persistWithParents(
        "merge",
        List.of("base", "feature"),
        3,
        List.of(),
        List.of(file("h3", OLD_PATH)),
        List.of(),
        List.of());

    final List<FileHistoryDto> history = historyOf("h3", OLD_PATH);

    assertEquals(List.of("base:ADDED", "feature:MODIFIED", "merge:MODIFIED"), describe(history));
    assertFalse(history.get(0).mergeCommit());
    assertFalse(history.get(1).mergeCommit());
    assertTrue(history.get(2).mergeCommit());
  }

  @Test
  void historyShowsDeletionWhenFileIsRemovedAndAddedAgain() {
    persistAdded("c1", null, 1, file("h1", OLD_PATH));
    persist("c2", "c1", 2, List.of(), List.of(), List.of(file("h1", OLD_PATH)), List.of());
    persistAdded("c3", "c2", 3, file("h3", OLD_PATH));

    final List<FileHistoryDto> history = historyOf("h3", OLD_PATH);

    assertEquals(List.of("c1:ADDED", "c2:DELETED", "c3:ADDED"), describe(history));
  }

  private List<FileHistoryDto> historyOf(final String hash, final String path) {
    final Long id =
        session.queryForObject(
            Long.class,
            "MATCH (f:FileRevision {hash: $hash, filePath: $path}) RETURN id(f)",
            Map.of("hash", hash, "path", path));
    assertNotNull(id);
    return fileHistoryRepository.fetchFileHistory(session, id);
  }

  private long idOf(final String hash) {
    final Long id =
        session.queryForObject(
            Long.class, "MATCH (f:FileRevision {hash: $hash}) RETURN id(f)", Map.of("hash", hash));
    assertNotNull(id);
    return id;
  }

  private static List<String> paths(final List<FileHistoryDto> history) {
    return history.stream().map(FileHistoryDto::path).toList();
  }

  private static List<String> describe(final List<FileHistoryDto> history) {
    return history.stream().map(entry -> entry.commitHash() + ":" + entry.action()).toList();
  }

  private List<Map<String, Object>> renameLinks() {
    final List<Map<String, Object>> links = new ArrayList<>();
    session
        .query(
            """
            MATCH (n:FileRevision)-[r:RENAMED_FROM]->(o:FileRevision)
            RETURN n.filePath AS newPath, n.hash AS newHash,
              o.filePath AS oldPath, o.hash AS oldHash,
              r.commitHash AS commitHash, r.similarity AS similarity
            """,
            Map.of())
        .queryResults()
        .forEach(links::add);
    return links;
  }

  private boolean commitContainsPath(final String commitHash, final String path) {
    return Boolean.TRUE.equals(
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $commitHash})-[:CONTAINS]->(:FileRevision {filePath: $path})
            } AS exists
            """,
            Map.of("commitHash", commitHash, "path", path)));
  }

  private void persistAdded(
      final String hash, final String parent, final long seconds, final FileIdentifier... added) {
    persist(hash, parent, seconds, List.of(added), List.of(), List.of(), List.of());
  }

  private void persistRename(
      final String hash,
      final String parent,
      final long seconds,
      final FileIdentifier oldFile,
      final FileIdentifier newFile,
      final int similarity) {
    persist(
        hash,
        parent,
        seconds,
        List.of(newFile),
        List.of(),
        List.of(oldFile),
        List.of(
            FileRename.newBuilder()
                .setOldFile(oldFile)
                .setNewFile(newFile)
                .setSimilarity(similarity)
                .build()));
  }

  private void persist(
      final String hash,
      final String parent,
      final long seconds,
      final List<FileIdentifier> added,
      final List<FileIdentifier> modified,
      final List<FileIdentifier> deleted,
      final List<FileRename> renamed) {
    persistWithParents(
        hash,
        parent != null ? List.of(parent) : List.of(),
        seconds,
        added,
        modified,
        deleted,
        renamed);
  }

  private void persistWithParents(
      final String hash,
      final List<String> parents,
      final long seconds,
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
            .addAllAddedFiles(added)
            .addAllModifiedFiles(modified)
            .addAllDeletedFiles(deleted)
            .addAllRenamedFiles(renamed);
    if (!parents.isEmpty()) {
      commit.setParentCommitId(parents.get(0));
      commit.addAllParentCommitIds(parents);
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
