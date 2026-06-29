package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.assertNodeCounts;
import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Tag;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.CommitService;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.proto.StateDataRequest;
import net.explorviz.landscape.proto.StateDataService;
import net.explorviz.landscape.util.ExpectedCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class CommitServiceTest {

  private static final long GRPC_AWAIT_SECONDS = 5;

  @GrpcClient CommitService commitService;

  @GrpcClient StateDataService stateDataService;

  @Inject SessionFactory sessionFactory;

  private Session session;
  private String landscapeToken;
  private String repoName;
  private String branchName;

  @BeforeEach
  void init() {
    session = sessionFactory.openSession();
    resetDatabase(session);

    landscapeToken = "mytokenvalue";
    repoName = "myrepo";
    branchName = "main";

    StateDataRequest stateDataRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .build();

    stateDataService
        .getStateData(stateDataRequest)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
  }

  @Test
  void testPersistCommit() {
    String commitHash = "commit1";
    String fileHashOne = "1";
    String fileNameOne = "File1.java";
    String filePathOne = "src/" + fileNameOne;
    String fileHashTwo = "2";
    String fileNameTwo = "File2.java";
    String filePathTwo = "src/" + fileNameTwo;
    String tagName = "tag";

    CommitData commitDataOne =
        CommitData.newBuilder()
            .setCommitId(commitHash)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .addAllTags(List.of(tagName))
            .addAllAddedFiles(
                List.of(
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashOne)
                        .setFilePath(filePathOne)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashTwo)
                        .setFilePath(filePathTwo)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Commit commit =
        session.queryForObject(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit {hash: $commitHash})
              -[:BELONGS_TO]->(:Branch {name: $branchName})
            RETURN c;
            """,
            Map.of(
                "landscapeToken",
                landscapeToken,
                "repoName",
                repoName,
                "commitHash",
                commitHash,
                "branchName",
                branchName));

    Tag tag =
        session.queryForObject(
            Tag.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(r:Repository {name: $repoName})
              -[:CONTAINS]->(:Commit {hash: $commitHash})
              -[:IS_TAGGED_WITH]->(t:Tag {name: $tagName})
            MATCH (r)-[:CONTAINS]->(t)
            RETURN t;
            """,
            Map.of(
                "landscapeToken",
                landscapeToken,
                "repoName",
                repoName,
                "commitHash",
                commitHash,
                "tagName",
                tagName));

    Iterable<FileRevision> files =
        session.query(
            FileRevision.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(:Commit {hash: $commitHash})
              -[:CONTAINS]->(f:FileRevision)
            RETURN f
            ORDER BY f.name ASC;
            """,
            Map.of(
                "landscapeToken", landscapeToken, "repoName", repoName, "commitHash", commitHash));

    Boolean correctRepoPath =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:HAS_ROOT]->(:Directory {name: $repoName})
              -[:CONTAINS]->(src:Directory {name: 'src'})

            MATCH (src)-[:CONTAINS]->(:FileRevision {name: $fileNameOne, hash: $fileHashOne})
            MATCH (src)-[:CONTAINS]->(:FileRevision {name: $fileNameTwo, hash: $fileHashTwo})
            } AS exists
            """,
            Map.of(
                "landscapeToken",
                landscapeToken,
                "repoName",
                repoName,
                "fileNameOne",
                fileNameOne,
                "fileHashOne",
                fileHashOne,
                "fileNameTwo",
                fileNameTwo,
                "fileHashTwo",
                fileHashTwo));

    int count = 0;
    for (FileRevision file : files) {
      count++;
    }
    Iterator<FileRevision> it = files.iterator();

    assertNotNull(commit);
    assertNotNull(tag);
    assertEquals(2, count);
    assertEquals(fileNameOne, it.next().getName());
    assertEquals(fileNameTwo, it.next().getName());
    assertTrue(correctRepoPath);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .files(2)
            .commits(1)
            .tags(1)
            .build());
  }

  @Test
  void testPersistCommitWithParentCommit() {
    String commitHashOne = "commit1";
    String commitHashTwo = "commit2";

    CommitData commitDataOne =
        CommitData.newBuilder()
            .setCommitId(commitHashOne)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    CommitData commitDataTwo =
        CommitData.newBuilder()
            .setCommitId(commitHashTwo)
            .setRepositoryName(repoName)
            .setParentCommitId(commitHashOne)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(30).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(30).setNanos(100).build())
            .build();

    commitService
        .persistCommit(commitDataTwo)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Boolean correctDatabase =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(r:Repository {name: $repoName})
              -[:CONTAINS]->(c1:Commit {hash: $commitHashOne})

            MATCH (r)
              -[:CONTAINS]->(:Commit {hash: $commitHashTwo})
              -[:HAS_PARENT]->(c1)
            } AS exists
            """,
            Map.of(
                "landscapeToken",
                landscapeToken,
                "repoName",
                repoName,
                "commitHashOne",
                commitHashOne,
                "commitHashTwo",
                commitHashTwo));

    assertTrue(correctDatabase);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(1)
            .commits(2)
            .build());
  }

  @Test
  void testPersistCommitAddedModifiedDeletedUnchangedFiles() throws InterruptedException {
    String commitHashOne = "commit1";
    String commitHashTwo = "commit2";
    String fileHashOne = "1";
    String fileHashOneMod = "11";
    String fileNameOne = "File1.java";
    String filePathOne = "src/" + fileNameOne;
    String fileHashTwo = "2";
    String fileNameTwo = "File2.java";
    String filePathTwo = "src/" + fileNameTwo;
    String fileHashThree = "3";
    String fileNameThree = "File3.java";
    String filePathThree = "src/" + fileNameThree;
    String fileHashDel = "4";
    String fileNameDel = "FileDel.java";
    String filePathDel = "src/" + fileNameDel;
    String tagName = "tag";

    CommitData commitDataOne =
        CommitData.newBuilder()
            .setCommitId(commitHashOne)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .addAllTags(List.of(tagName))
            .addAllAddedFiles(
                List.of(
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashOne)
                        .setFilePath(filePathOne)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashTwo)
                        .setFilePath(filePathTwo)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashDel)
                        .setFilePath(filePathDel)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    CommitData commitDataTwo =
        CommitData.newBuilder()
            .setCommitId(commitHashTwo)
            .setRepositoryName(repoName)
            .setParentCommitId(commitHashOne)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(30).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(30).setNanos(100).build())
            .addAllTags(List.of(tagName))
            .addAllAddedFiles(
                List.of(
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashThree)
                        .setFilePath(filePathThree)
                        .build()))
            .addAllModifiedFiles(
                List.of(
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashOneMod)
                        .setFilePath(filePathOne)
                        .build()))
            .addAllDeletedFiles(
                List.of(
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashDel)
                        .setFilePath(filePathDel)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataTwo)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("fileNameOne", fileNameOne);
    params.put("fileHashOne", fileHashOne);
    params.put("fileNameTwo", fileNameTwo);
    params.put("fileHashTwo", fileHashTwo);
    params.put("fileNameThree", fileNameThree);
    params.put("fileHashThree", fileHashThree);
    params.put("fileHashOneMod", fileHashOneMod);
    params.put("fileNameDel", fileNameDel);
    params.put("fileHashDel", fileHashDel);
    params.put("commitHashOne", commitHashOne);
    params.put("commitHashTwo", commitHashTwo);
    params.put("tagName", tagName);
    params.put("branchName", branchName);

    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(r:Repository {name: $repoName})
              -[:HAS_ROOT]->(:Directory {name: $repoName})
              -[:CONTAINS]->(src:Directory {name: 'src'})

            MATCH (r)-[:CONTAINS]->(c1:Commit {hash: $commitHashOne})
            MATCH (c1)-[:CONTAINS]->(f1:FileRevision {name: $fileNameOne, hash: $fileHashOne})
            MATCH (src)-[:CONTAINS]->(f1)
            MATCH (c1)-[:CONTAINS]->(f2:FileRevision {name: $fileNameTwo, hash: $fileHashTwo})
            MATCH (src)-[:CONTAINS]->(f2)
            MATCH (c1)-[:CONTAINS]->(f_del:FileRevision {name: $fileNameDel, hash: $fileHashDel})
            MATCH (src)-[:CONTAINS]->(f_del)
            MATCH (c1)-[:IS_TAGGED_WITH]->(t:Tag {name: $tagName})
            MATCH (c1)-[:BELONGS_TO]->(branch:Branch {name: $branchName})

            MATCH (r)-[:CONTAINS]->(c2:Commit {hash: $commitHashTwo})
            MATCH (c2)-[:CONTAINS]->(f3:FileRevision {name: $fileNameThree, hash: $fileHashThree})
            MATCH (src)-[:CONTAINS]->(f3)
            MATCH (c2)-[:CONTAINS]->(f1_mod:FileRevision {name: $fileNameOne, hash: $fileHashOneMod})
            MATCH (src)-[:CONTAINS]->(f1_mod)
            MATCH (c2)-[:IS_TAGGED_WITH]->(t:Tag {name: $tagName})
            MATCH (c2)-[:CONTAINS]->(f2:FileRevision {name: $fileNameTwo, hash: $fileHashTwo})
            MATCH (c2)-[:BELONGS_TO]->(branch)

            MATCH (r)-[:CONTAINS]->(t)

            WHERE NOT EXISTS {
              MATCH (c2)-[:CONTAINS]->(f_del)
            }
            } AS exists
            """,
            params);

    Integer dbSize =
        session.queryForObject(
            Integer.class,
            """
            MATCH (n) RETURN count(n);
            """,
            Map.of());

    assertEquals(13, dbSize);
    assertTrue(databaseIsCorrect);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .files(5)
            .commits(2)
            .tags(1)
            .build());
  }

  @Test
  void testPersistCommitWithUnknownRepo() {
    String wrongRepoName = "wrong_repo";
    String commitHash = "commit1";

    CommitData commitDataOne =
        CommitData.newBuilder()
            .setCommitId(commitHash)
            .setRepositoryName(wrongRepoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .build();

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("branchName", branchName);

    String dbQuery =
        """
        RETURN EXISTS {
        MATCH (:Landscape {tokenId: $landscapeToken})
          -[:CONTAINS]->(r:Repository {name: $repoName})
          -[:CONTAINS]->(:Branch {name: $branchName})

        MATCH (r)
          -[:HAS_ROOT]->(:Directory {name: $repoName})
        } AS exists
        """;

    Boolean databaseCorrectBeforePersistCommit =
        session.queryForObject(Boolean.class, dbQuery, params);

    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                commitService
                    .persistCommit(commitDataOne)
                    .await()
                    .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS)));

    Boolean databaseCorrectAfterPersistCommit =
        session.queryForObject(Boolean.class, dbQuery, params);

    assertTrue(databaseCorrectBeforePersistCommit);
    assertEquals(Status.FAILED_PRECONDITION.getCode(), ex.getStatus().getCode());
    assertEquals("No corresponding state data was sent before.", ex.getStatus().getDescription());
    assertTrue(databaseCorrectAfterPersistCommit);
    assertNodeCounts(
        session,
        ExpectedCounts.builder().landscapes(1).repositories(1).directories(1).branches(1).build());
  }

  @Test
  void testPersistBootstrapCommitWithExplicitUnchangedFiles() {
    final String parentHash = "parent-not-in-landscape";
    final String commitHash = "bootstrap-commit";
    final String addedPath = "src/Added.java";
    final String unchangedPath = "src/Unchanged.java";

    final CommitData commitData =
        CommitData.newBuilder()
            .setCommitId(commitHash)
            .setParentCommitId(parentHash)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .addAddedFiles(
                FileIdentifier.newBuilder()
                    .setFileHash("added-hash")
                    .setFilePath(addedPath)
                    .build())
            .addUnchangedFiles(
                FileIdentifier.newBuilder()
                    .setFileHash("unchanged-hash")
                    .setFilePath(unchangedPath)
                    .build())
            .build();

    commitService.persistCommit(commitData).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final Boolean addedRelationshipExists =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $commitHash})-[:ADDED]->(:FileRevision {hash: 'added-hash'})
            } AS exists
            """,
            Map.of("commitHash", commitHash));

    final Boolean unchangedRelationshipExists =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $commitHash})-[:CONTAINS]->(:FileRevision {hash: 'unchanged-hash'})
            } AND NOT EXISTS {
              MATCH (:Commit {hash: $commitHash})-[:ADDED|MODIFIED]->(:FileRevision {hash: 'unchanged-hash'})
            } AS exists
            """,
            Map.of("commitHash", commitHash));

    assertTrue(addedRelationshipExists);
    assertTrue(unchangedRelationshipExists);
  }

  @Test
  void testIncrementalCommitCopiesUnchangedFilesWhenAnalyzerOmitsThem() {
    final String parentHash = "parent-commit";
    final String childHash = "child-commit";
    final String unchangedPath = "src/Unchanged.java";
    final String modifiedPath = "src/Modified.java";

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
                .addAllAddedFiles(
                    List.of(
                        FileIdentifier.newBuilder()
                            .setFileHash("unchanged-hash")
                            .setFilePath(unchangedPath)
                            .build(),
                        FileIdentifier.newBuilder()
                            .setFileHash("old-modified-hash")
                            .setFilePath(modifiedPath)
                            .build()))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(childHash)
                .setParentCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(2).build())
                .addAllModifiedFiles(
                    List.of(
                        FileIdentifier.newBuilder()
                            .setFileHash("new-modified-hash")
                            .setFilePath(modifiedPath)
                            .build()))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final Boolean unchangedCopied =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {
                hash: 'unchanged-hash', name: 'Unchanged.java'
              })
            } AS exists
            """,
            Map.of("childHash", childHash));

    final Boolean modifiedUsesNewRevision =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:MODIFIED]->(:FileRevision {hash: 'new-modified-hash'})
            } AS exists
            """,
            Map.of("childHash", childHash));

    assertTrue(unchangedCopied);
    assertTrue(modifiedUsesNewRevision);
  }

  @Test
  void testDeletedFileIsNotLinkedToChildCommit() {
    final String parentHash = "parent-commit-del";
    final String childHash = "child-commit-del";
    final String deletedPath = "src/Removed.java";

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
                .addAddedFiles(
                    FileIdentifier.newBuilder()
                        .setFileHash("removed-hash")
                        .setFilePath(deletedPath)
                        .build())
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(childHash)
                .setParentCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(2).build())
                .addDeletedFiles(
                    FileIdentifier.newBuilder()
                        .setFileHash("removed-hash")
                        .setFilePath(deletedPath)
                        .build())
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final Boolean deletedFileAbsentOnChild =
        session.queryForObject(
            Boolean.class,
            """
            RETURN NOT EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {
                filePath: $deletedPath
              })
            } AS absent
            """,
            Map.of("childHash", childHash, "deletedPath", deletedPath));

    final Boolean deletedFileStillOnParent =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $parentHash})-[:CONTAINS]->(:FileRevision {
                filePath: $deletedPath
              })
            } AS exists
            """,
            Map.of("parentHash", parentHash, "deletedPath", deletedPath));

    assertTrue(deletedFileAbsentOnChild);
    assertTrue(deletedFileStillOnParent);
  }

  @Test
  void testIncrementalCommitCopiesAllUnchangedFilesFromMultiFileParent() {
    final String parentHash = "multi-parent";
    final String childHash = "multi-child";

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
                .addAllAddedFiles(
                    List.of(
                        fileId("hash-a", "src/A.java"),
                        fileId("hash-b", "src/B.java"),
                        fileId("hash-c", "src/C.java"),
                        fileId("hash-d", "src/D.java")))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(childHash)
                .setParentCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(2).build())
                .addModifiedFiles(fileId("hash-b-mod", "src/B.java"))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final Integer childFileCount =
        session.queryForObject(
            Integer.class,
            """
            MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(f:FileRevision)
            RETURN count(f) AS fileCount
            """,
            Map.of("childHash", childHash));

    final Boolean hasUnchangedA =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {hash: 'hash-a'})
            } AS exists
            """,
            Map.of("childHash", childHash));
    final Boolean hasUnchangedC =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {hash: 'hash-c'})
            } AS exists
            """,
            Map.of("childHash", childHash));
    final Boolean hasUnchangedD =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {hash: 'hash-d'})
            } AS exists
            """,
            Map.of("childHash", childHash));
    final Boolean hasModifiedB =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:MODIFIED]->(:FileRevision {hash: 'hash-b-mod'})
            } AS exists
            """,
            Map.of("childHash", childHash));
    final Boolean lacksOldB =
        session.queryForObject(
            Boolean.class,
            """
            RETURN NOT EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {hash: 'hash-b'})
            } AS absent
            """,
            Map.of("childHash", childHash));

    assertEquals(4, childFileCount);
    assertTrue(hasUnchangedA);
    assertTrue(hasUnchangedC);
    assertTrue(hasUnchangedD);
    assertTrue(hasModifiedB);
    assertTrue(lacksOldB);
  }

  @Test
  void testChainedIncrementalCommitsCopyFullFileSet() {
    final String parentHash = "chain-parent";
    final String middleHash = "chain-middle";
    final String childHash = "chain-child";

    final List<FileIdentifier> parentFiles =
        List.of(
            fileId("f01", "src/F01.java"),
            fileId("f02", "src/F02.java"),
            fileId("f03", "src/F03.java"),
            fileId("f04", "src/F04.java"),
            fileId("f05", "src/F05.java"),
            fileId("f06", "src/F06.java"),
            fileId("f07", "src/F07.java"),
            fileId("f08", "src/F08.java"));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
                .addAllAddedFiles(parentFiles)
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(middleHash)
                .setParentCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(2).build())
                .addModifiedFiles(fileId("f05-mid", "src/F05.java"))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(childHash)
                .setParentCommitId(middleHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(3).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(3).build())
                .addModifiedFiles(fileId("f01-child", "src/F01.java"))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    for (final String hash :
        List.of("f01-child", "f05-mid", "f02", "f03", "f04", "f06", "f07", "f08")) {
      final Boolean linked =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {hash: $hash})
              } AS exists
              """,
              Map.of("childHash", childHash, "hash", hash));
      assertTrue(linked, "Expected child commit to contain file revision " + hash);
    }

    final Integer childFileCount =
        session.queryForObject(
            Integer.class,
            """
            MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision)
            RETURN count(*) AS fileCount
            """,
            Map.of("childHash", childHash));
    assertEquals(8, childFileCount);
  }

  @Test
  void testIncrementalCommitCopiesUnchangedFilesWithMissingFilePath() {
    final String parentHash = "null-path-parent";
    final String childHash = "null-path-child";
    final String unchangedPath = "src/LegacyUnchanged.java";
    final String modifiedPath = "src/Modified.java";

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
                .addAllAddedFiles(
                    List.of(
                        fileId("legacy-unchanged-hash", unchangedPath),
                        fileId("old-modified-hash", modifiedPath)))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    session.query(
        """
        MATCH (f:FileRevision {hash: 'legacy-unchanged-hash'})
        REMOVE f.filePath
        """,
        Map.of());

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(childHash)
                .setParentCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(2).build())
                .addModifiedFiles(fileId("new-modified-hash", modifiedPath))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final Boolean unchangedCopied =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {
                hash: 'legacy-unchanged-hash'
              })
            } AS exists
            """,
            Map.of("childHash", childHash));

    assertTrue(unchangedCopied);
  }

  @Test
  void testModifiedCommitLinksOnlyNewRevisionAtPath() {
    final String parentHash = "parent-single-mod";
    final String childHash = "child-single-mod";
    final String modifiedPath = "src/OnlyModified.java";

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
                .addAddedFiles(fileId("old-hash", modifiedPath))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(childHash)
                .setParentCommitId(parentHash)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).build())
                .setCommitDate(Timestamp.newBuilder().setSeconds(2).build())
                .addModifiedFiles(fileId("new-hash", modifiedPath))
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final Integer containsCount =
        session.queryForObject(
            Integer.class,
            """
            MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(f:FileRevision {filePath: $modifiedPath})
            RETURN count(f) AS fileCount
            """,
            Map.of("childHash", childHash, "modifiedPath", modifiedPath));

    final Boolean containsNewHash =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
              MATCH (:Commit {hash: $childHash})-[:CONTAINS]->(:FileRevision {
                filePath: $modifiedPath, hash: 'new-hash'
              })
            } AS exists
            """,
            Map.of("childHash", childHash, "modifiedPath", modifiedPath));

    assertEquals(1, containsCount);
    assertTrue(containsNewHash);
  }

  private static FileIdentifier fileId(final String hash, final String path) {
    return FileIdentifier.newBuilder().setFileHash(hash).setFilePath(path).build();
  }
}
