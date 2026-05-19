package net.explorviz.persistence;

import static net.explorviz.persistence.util.TestUtils.assertNodeCounts;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.persistence.ogm.Application;
import net.explorviz.persistence.ogm.Branch;
import net.explorviz.persistence.ogm.Directory;
import net.explorviz.persistence.ogm.Landscape;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.proto.CommitData;
import net.explorviz.persistence.proto.CommitService;
import net.explorviz.persistence.proto.FileData;
import net.explorviz.persistence.proto.FileDataService;
import net.explorviz.persistence.proto.FileIdentifier;
import net.explorviz.persistence.proto.Language;
import net.explorviz.persistence.proto.StateData;
import net.explorviz.persistence.proto.StateDataRequest;
import net.explorviz.persistence.proto.StateDataService;
import net.explorviz.persistence.util.ExpectedCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class StateDataServiceTest {

  private static final long GRPC_AWAIT_SECONDS = 5;

  @GrpcClient CommitService commitService;

  @GrpcClient FileDataService fileDataService;

  @GrpcClient StateDataService stateDataService;

  @Inject SessionFactory sessionFactory;

  private Session session;
  private String landscapeToken;
  private String repoName;
  private String branchName;

  @BeforeEach
  void init() {
    session = sessionFactory.openSession();
    session.purgeDatabase();

    landscapeToken = "mytokenvalue";
    repoName = "myrepo";
    branchName = "main";
  }

  @Test
  void testGetStateDataOnEmptyDB() {
    String appName = "testApp";

    StateDataRequest stateDataRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appName, ""))
            .build();

    StateData stateData =
        stateDataService
            .getStateData(stateDataRequest)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Landscape landscape =
        session.queryForObject(
            Landscape.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            RETURN l;
            """,
            Map.of("tokenId", landscapeToken));

    Repository repository =
        session.queryForObject(
            Repository.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(r:Repository {name: $repoName})
            RETURN r;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName));

    Directory rootDirectory =
        session.queryForObject(
            Directory.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(root:Directory {name: $repoName})
            RETURN root;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName));

    Application application =
        session.queryForObject(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(:Directory {name: $repoName})
                  <-[:HAS_ROOT]-(a:Application {name: $appName})
                  <-[:CONTAINS]-(l)
            RETURN a;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "appName", appName));

    Branch branch =
        session.queryForObject(
            Branch.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:CONTAINS]->(b:Branch {name: $branchName})
            RETURN b;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "branchName", branchName));

    assertEquals("", stateData.getCommitId());
    assertNotNull(landscape);
    assertNotNull(repository);
    assertNotNull(rootDirectory);
    assertNotNull(application);
    assertNotNull(branch);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .applications(1)
            .directories(1)
            .build());
  }

  @Test
  void testGetStateDataWithMultipleApplications() {
    String appNameOne = "testAppOne";
    String appPathOne = "src/" + appNameOne;
    String appNameTwo = "testAppTwo";
    String appPathTwo = "src/org/" + appNameTwo;

    StateDataRequest stateDataRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appNameOne, appPathOne, appNameTwo, appPathTwo))
            .build();

    StateData stateData =
        stateDataService
            .getStateData(stateDataRequest)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Application applicationOne =
        session.queryForObject(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(:Directory {name: $repoName})
                  -[:CONTAINS]->(:Directory {name: 'src'})
                  -[:CONTAINS]->(:Directory {name: $appName})
                  <-[:HAS_ROOT]-(a:Application {name: $appName})
                  <-[:CONTAINS]-(l)
            RETURN a;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "appName", appNameOne));

    Application applicationTwo =
        session.queryForObject(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(:Directory {name: $repoName})
                  -[:CONTAINS]->(:Directory {name: 'src'})
                  -[:CONTAINS]->(:Directory {name: 'org'})
                  -[:CONTAINS]->(:Directory {name: $appName})
                  <-[:HAS_ROOT]-(a:Application {name: $appName})
                  <-[:CONTAINS]-(l)
            RETURN a;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "appName", appNameTwo));

    assertEquals("", stateData.getCommitId());
    assertNotNull(applicationOne);
    assertNotNull(applicationTwo);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .applications(2)
            .directories(5)
            .build());
  }

  @Test
  void testGetStateDataWithExistingCommits() {
    String appName = "testApp";
    String commitHash = "commit1";
    String fileHash = "1";
    String filePath = "src/File1.java";

    StateDataRequest stateDataPreparationRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appName, ""))
            .build();

    stateDataService
        .getStateData(stateDataPreparationRequest)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    CommitData commitDataOne =
        CommitData.newBuilder()
            .setCommitId(commitHash)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .addAllAddedFiles(
                List.of(
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHash)
                        .setFilePath(filePath)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Double> testMap = Map.of("count", 1d, "lines", 2d);

    FileData fileDataOne =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHash)
            .setFilePath(filePath)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Test"))
            .addAllClasses(List.of())
            .addAllFunctions(List.of())
            .putAllMetrics(testMap)
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    fileDataService.persistFile(fileDataOne).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    StateDataRequest stateDataRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appName, ""))
            .build();

    StateData stateData =
        stateDataService
            .getStateData(stateDataRequest)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Landscape landscape =
        session.queryForObject(
            Landscape.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            RETURN l;
            """,
            Map.of("tokenId", landscapeToken));

    Repository repository =
        session.queryForObject(
            Repository.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(r:Repository {name: $repoName})
            RETURN r;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName));

    Directory rootDirectory =
        session.queryForObject(
            Directory.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(root:Directory {name: $repoName})
            RETURN root;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName));

    Application application =
        session.queryForObject(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(:Directory {name: $repoName})
                  <-[:HAS_ROOT]-(a:Application {name: $appName})
                  <-[:CONTAINS]-(l)
            RETURN a;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "appName", appName));

    Branch branch =
        session.queryForObject(
            Branch.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:CONTAINS]->(b:Branch {name: $branchName})
            RETURN b;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "branchName", branchName));

    assertEquals(commitHash, stateData.getCommitId());
    assertNotNull(landscape);
    assertNotNull(repository);
    assertNotNull(rootDirectory);
    assertNotNull(application);
    assertNotNull(branch);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .applications(1)
            .directories(2)
            .files(1)
            .commits(1)
            .build());
  }

  @Test
  void testGetStateDataForTwoDifferentReposInOneLandscape() {
    String repoNameTwo = "repo2";
    String appName = "testApp";
    String appNameTwo = "app2";

    StateDataRequest stateDataRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appName, ""))
            .build();

    StateData stateData =
        stateDataService
            .getStateData(stateDataRequest)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    StateDataRequest stateDataRequestTwo =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoNameTwo)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appNameTwo, ""))
            .build();

    StateData stateDataTwo =
        stateDataService
            .getStateData(stateDataRequestTwo)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoNameOne", repoName);
    params.put("repoNameTwo", repoNameTwo);
    params.put("appNameOne", appName);
    params.put("appNameTwo", appNameTwo);
    params.put("branchName", branchName);

    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (l:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(r1:Repository {name: $repoNameOne})
              -[:HAS_ROOT]->(:Directory {name: $repoNameOne})
              <-[:HAS_ROOT]-(:Application {name: $appNameOne})
              <-[:CONTAINS]-(l)
            MATCH (r1)-[:CONTAINS]->(b1:Branch {name: $branchName})

            MATCH (l)
              -[:CONTAINS]->(r2:Repository {name: $repoNameTwo})
              -[:HAS_ROOT]->(:Directory {name: $repoNameTwo})
              <-[:HAS_ROOT]-(:Application {name: $appNameTwo})
              <-[:CONTAINS]-(l)
            MATCH (r2)-[:CONTAINS]->(b2:Branch {name: $branchName})

            WHERE b1 <> b2
            } AS exists
            """,
            params);

    assertEquals("", stateData.getCommitId());
    assertEquals("", stateDataTwo.getCommitId());
    assertTrue(databaseIsCorrect);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(2)
            .branches(2)
            .applications(2)
            .directories(2)
            .build());
  }

  @Test
  void testGetStateDataForNewApplicationInExistingRepo() {
    String appNameOne = "app1";
    String appNameTwo = "app2";
    String commitHash = "commit1";

    // 1. Setup app1 and a commit with a file
    StateDataRequest prepRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appNameOne, "app1"))
            .build();
    stateDataService
        .getStateData(prepRequest)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    CommitData commitData =
        CommitData.newBuilder()
            .setCommitId(commitHash)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).build())
            .setCommitDate(Timestamp.newBuilder().setSeconds(1).build())
            .addAddedFiles(
                FileIdentifier.newBuilder().setFileHash("hash1").setFilePath("app1/File1.java"))
            .build();
    commitService.persistCommit(commitData).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    FileData fileData =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash("hash1")
            .setFilePath("app1/File1.java")
            .setLanguage(Language.JAVA)
            .build();
    fileDataService.persistFile(fileData).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    // 2. Request state for app1 - should return commit1
    StateData state1 =
        stateDataService
            .getStateData(prepRequest)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
    assertEquals(commitHash, state1.getCommitId());

    // 3. Request state for app1 AND app2 - should return ""
    StateDataRequest bothRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .putAllApplicationPaths(Map.of(appNameOne, "app1", appNameTwo, "app2"))
            .build();
    StateData stateBoth =
        stateDataService
            .getStateData(bothRequest)
            .await()
            .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
    assertEquals("", stateBoth.getCommitId());
  }
}
