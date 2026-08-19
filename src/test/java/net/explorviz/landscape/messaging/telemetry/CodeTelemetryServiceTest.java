package net.explorviz.landscape.messaging.telemetry;

import static net.explorviz.landscape.util.TestUtils.assertNodeCounts;
import static net.explorviz.landscape.util.TestUtils.createLandscape;
import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.messaging.TelemetryConsumer;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.Directory;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.ogm.Landscape;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.util.ExpectedCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class CodeTelemetryServiceTest {

  @Inject TelemetryConsumer telemetryConsumer;

  @Inject SessionFactory sessionFactory;

  private Session session;
  private String landscapeToken;
  private String baseAppName;

  @BeforeEach
  void cleanup() {
    landscapeToken = "mytokenvalue";
    baseAppName = "myApp";

    session = sessionFactory.openSession();
    resetDatabase(session);
    createLandscape(session, landscapeToken);
  }

  @Nested
  class WithoutStaticData {
    private List<String> baseDirNames;
    private String baseFileName;
    private List<String> baseFilePath;
    private String baseFunctionName;

    @BeforeEach
    void init() {
      baseDirNames = List.of("net", "explorviz", baseAppName);
      baseFileName = "MyClass.java";
      baseFilePath = ImmutableList.<String>builder().addAll(baseDirNames).add(baseFileName).build();
      baseFunctionName = "myMethod";
    }

    TelemetryEntity.Builder baseEntityBuilder() {
      return TelemetryEntity.newBuilder()
          .setLandscapeTokenId(landscapeToken)
          .setCodeDescriptor(
              CodeDescriptor.newBuilder()
                  .setApplicationName(baseAppName)
                  .setFilePath(String.join("/", baseFilePath))
                  .setFunctionName(baseFunctionName));
    }

    @Test
    void testPersistEntity() {
      TelemetryEntity testEntity = baseEntityBuilder().build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("funName", baseFunctionName);

      Application result =
          session.queryForObject(
              Application.class,
              """
              MATCH (:Landscape {tokenId: $landscapeToken})
                -[:CONTAINS]->(app:Application {name: $appName})
                -[:HAS_ROOT]->(:Directory)
                -[:CONTAINS]->(:Directory {name: $dirOne})
                -[:CONTAINS]->(:Directory {name: $dirTwo})
                -[:CONTAINS]->(:Directory {name: $dirThree})
                -[:CONTAINS]->(:FileRevision {name: $fileName})
                -[:CONTAINS]->(:Function {name: $funName})
              RETURN app;
              """,
              params);

      assertNotNull(result);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .functions(1)
              .build());
    }

    /** Persisting the same entity twice should create no additional nodes. */
    @Test
    void testPersistEntityIdempotent() {
      TelemetryEntity testEntity = baseEntityBuilder().build();

      String dbStructureQuery =
          """
          RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(app:Application {name: $appName})
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS]->(:Directory {name: $dirOne})
              -[:CONTAINS]->(:Directory {name: $dirTwo})
              -[:CONTAINS]->(:Directory {name: $dirThree})
              -[:CONTAINS]->(:FileRevision {name: $fileName})
              -[:CONTAINS]->(:Function {name: $funName})
          } as exists;
          """;

      String dbNodeCountQuery =
          """
          RETURN COUNT { MATCH (n) RETURN n };
          """;

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("funName", baseFunctionName);

      telemetryConsumer.consume(testEntity.toByteArray());

      Boolean dbIsCorrectAfterFirstConsumeCall =
          session.queryForObject(Boolean.class, dbStructureQuery, params);
      Long nodeCountAfterFirstConsumeCall =
          session.queryForObject(Long.class, dbNodeCountQuery, Map.of());

      telemetryConsumer.consume(testEntity.toByteArray());

      Boolean dbIsCorrectAfterSecondConsumeCall =
          session.queryForObject(Boolean.class, dbStructureQuery, params);
      Long nodeCountAfterSecondConsumeCall =
          session.queryForObject(Long.class, dbNodeCountQuery, Map.of());

      assertTrue(dbIsCorrectAfterFirstConsumeCall);
      assertTrue(dbIsCorrectAfterSecondConsumeCall);
      assertEquals(nodeCountAfterFirstConsumeCall, nodeCountAfterSecondConsumeCall);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .functions(1)
              .build());
    }

    @Test
    void testPersistEntitiesMultipleFiles() {
      String functionNameTwo = "yourMethod";
      String fileNameTwo = "YourClass.java";
      List<String> filePathTwo =
          ImmutableList.<String>builder().addAll(baseDirNames).add(fileNameTwo).build();

      TelemetryEntity testEntity = baseEntityBuilder().build();

      telemetryConsumer.consume(testEntity.toByteArray());

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", filePathTwo)))
              .build();

      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileNameOne", baseFileName);
      params.put("fileNameTwo", fileNameTwo);
      params.put("funName", baseFunctionName);
      params.put("funName2", functionNameTwo);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(d3:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileNameOne})
                  -[:CONTAINS]->(fun1:Function {name: $funName})

                MATCH (d3)
                  -[:CONTAINS]->(file2:FileRevision {name: $fileNameTwo})
                  -[:CONTAINS]->(fun2:Function {name: $funName2})
                WHERE fun1 <> fun2
              } AS exists
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(2)
              .functions(2)
              .build());
    }

    @Test
    void testPersistEntityMultipleFunctions() {
      String functionNameTwo = "yourMethod";

      TelemetryEntity testEntity = baseEntityBuilder().build();

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", baseFilePath)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());
      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("funName", baseFunctionName);
      params.put("funName2", functionNameTwo);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName})
                  -[:CONTAINS]->(fun1:Function {name: $funName})

                MATCH (file)-[:CONTAINS]->(fun2:Function {name: $funName2})
                WHERE fun1 <> fun2
              } AS exists
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .functions(2)
              .build());
    }

    /**
     * If entity with commit is persisted and no static data is present, then the entity should be
     * treated as if no commit hash was attached
     */
    @Test
    void testPersistEntityWithCommitWithoutStaticData() {
      String commitHash = "commit1";

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFilePath(String.join("/", baseFilePath))
                      .setFunctionName(baseFunctionName))
              .setGitCommitHash(commitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("funName", baseFunctionName);

      Application result =
          session.queryForObject(
              Application.class,
              """
              MATCH (:Landscape {tokenId: $landscapeToken})
                -[:CONTAINS]->(app:Application {name: $appName})
                -[:HAS_ROOT]->(:Directory)
                -[:CONTAINS]->(:Directory {name: $dirOne})
                -[:CONTAINS]->(:Directory {name: $dirTwo})
                -[:CONTAINS]->(:Directory {name: $dirThree})
                -[:CONTAINS]->(:FileRevision {name: $fileName})
                -[:CONTAINS]->(:Function {name: $funName})
              RETURN app;
              """,
              params);

      Commit commit =
          session.queryForObject(
              Commit.class,
              """
              MATCH (c:Commit {hash: $commitHash})
              RETURN c;
              """,
              Map.of("commitHash", commitHash));

      assertNotNull(result);
      assertNull(commit);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .functions(1)
              .commits(0)
              .build());
    }

    @Test
    void testPersistEntityWithPartOfFunctionPathAlreadyExisting() {
      String functionNameTwo = "acting";
      String fileNameTwo = "TheClass.java";
      List<String> filePathTwo =
          ImmutableList.<String>builder()
              .addAll(baseDirNames)
              .add("inner")
              .add(fileNameTwo)
              .build();

      TelemetryEntity testEntity = baseEntityBuilder().build();

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", filePathTwo)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());
      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileNameOne", baseFileName);
      params.put("dirFour", filePathTwo.get(3));
      params.put("fileNameTwo", fileNameTwo);
      params.put("funName", baseFunctionName);
      params.put("funNameTwo", functionNameTwo);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(sharedDir:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileNameOne})
                  -[:CONTAINS]->(fun1:Function {name: $funName})

                MATCH (sharedDir)-[:CONTAINS]->(:Directory {name: $dirFour})
                  -[:CONTAINS]->(file2:FileRevision {name: $fileNameTwo})
                  -[:CONTAINS]->(fun2:Function {name: $funNameTwo})
              } AS exists
              """,
              params);

      Result result =
          session.query(
              """
              RETURN
                COUNT {(:Directory {name: $dirOne})} AS dirOne,
                COUNT {(:Directory {name: $dirTwo})} AS dirTwo,
                COUNT {(:Directory {name: $dirThree})} AS dirThree,
                COUNT {(:Directory {name: $dirFour})} AS dirFour;
              """,
              params);

      Map<String, Object> countMap = result.queryResults().iterator().next();
      assertEquals(1, (Long) countMap.get("dirOne"));
      assertEquals(1, (Long) countMap.get("dirTwo"));
      assertEquals(1, (Long) countMap.get("dirThree"));
      assertEquals(1, (Long) countMap.get("dirFour"));
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(5)
              .files(2)
              .functions(2)
              .build());
      assertTrue(databaseIsCorrect);
    }

    @Test
    void testPersistEntityWithClassPathWithoutCommitHashWithNoClassesExisting() {
      String[] classPath = {"A", "B", "C"};

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("classNameOne", classPath[0]);
      params.put("classNameTwo", classPath[1]);
      params.put("classNameThree", classPath[2]);
      params.put("funName", baseFunctionName);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(:FileRevision {name: $fileName})
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(:Clazz {name: $classNameThree})
                  -[:CONTAINS]->(:Function {name: $funName})
              } as exists;
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .classes(3)
              .functions(1)
              .build());
    }

    @Test
    void testPersistEntityWithClassPathWithoutCommitHashWithAllClassesExisting() {
      String[] classPath = {"A", "B", "C"};
      String functionNameTwo = "function2";

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .build();

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());
      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("classNameOne", classPath[0]);
      params.put("classNameTwo", classPath[1]);
      params.put("classNameThree", classPath[2]);
      params.put("funName", baseFunctionName);
      params.put("funNameTwo", functionNameTwo);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(:FileRevision {name: $fileName})
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(c:Clazz {name: $classNameThree})
                  -[:CONTAINS]->(f1:Function {name: $funName})

                MATCH (c)-[:CONTAINS]->(f2:Function {name: $funNameTwo})
                WHERE f1 <> f2
              } as exists;
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .classes(3)
              .functions(2)
              .build());
    }

    @Test
    void testPersistEntityWithClassPathWithoutCommitHashWithSomeClassesExisting() {
      String[] classPath = {"A", "B"};
      String[] classPathTwo = ObjectArrays.concat(classPath, "C");
      String functionNameTwo = "function2";

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .build();

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPathTwo)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());
      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("classNameOne", classPath[0]);
      params.put("classNameTwo", classPath[1]);
      params.put("classNameThree", classPathTwo[2]);
      params.put("funName", baseFunctionName);
      params.put("funNameTwo", functionNameTwo);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(:FileRevision {name: $fileName})
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(c2:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(f1:Function {name: $funName})

                MATCH (c)
                  -[:CONTAINS]->(c3:Clazz {name: $classNameThree})
                  -[:CONTAINS]->(f2:Function {name: $funNameTwo})

                WHERE f1 <> f2
                  AND c2 <> c3
              } as exists;
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .classes(3)
              .functions(2)
              .build());
    }

    @Test
    void testPersistEntityWithClassPathWithCommitHashWithNoClassesExisting() {
      String[] classPath = {"A", "B", "C"};
      String commitHash = "commit1";

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .setGitCommitHash(commitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("classNameOne", classPath[0]);
      params.put("classNameTwo", classPath[1]);
      params.put("classNameThree", classPath[2]);
      params.put("funName", baseFunctionName);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(:FileRevision {name: $fileName})
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(:Clazz {name: $classNameThree})
                  -[:CONTAINS]->(:Function {name: $funName})
                } as exists;
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .classes(3)
              .functions(1)
              .build());
    }

    @Test
    void testPersistEntityWithClassPathWithCommitHashWithAllClassesExisting() {
      String[] classPath = {"A", "B", "C"};
      String functionNameTwo = "function2";
      String commitHash = "commit1";

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .setGitCommitHash(commitHash)
              .build();

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", baseFilePath))
                      .setClassName(String.join(".", classPath)))
              .setGitCommitHash(commitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());
      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", baseFilePath.get(0));
      params.put("dirTwo", baseFilePath.get(1));
      params.put("dirThree", baseFilePath.get(2));
      params.put("fileName", baseFileName);
      params.put("classNameOne", classPath[0]);
      params.put("classNameTwo", classPath[1]);
      params.put("classNameThree", classPath[2]);
      params.put("funName", baseFunctionName);
      params.put("funNameTwo", functionNameTwo);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(:FileRevision {name: $fileName})
                  -[:CONTAINS]->(:Clazz {name: $classNameOne})
                  -[:CONTAINS]->(:Clazz {name: $classNameTwo})
                  -[:CONTAINS]->(c:Clazz {name: $classNameThree})
                  -[:CONTAINS]->(f1:Function {name: $funName})

                MATCH (c)-[:CONTAINS]->(f2:Function {name: $funNameTwo})
                WHERE f1 <> f2
              } as exists;
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .applications(1)
              .directories(4)
              .files(1)
              .classes(3)
              .functions(2)
              .build());
    }
  }

  @Nested
  class WithStaticData {
    private String baseRepoName;
    private String baseBranchName;
    private String baseCommitHash;
    private List<String> baseDirNames;
    private String baseFileName;
    private List<String> baseFilePath;
    private String baseFileHash;
    private String baseFunctionName;

    private void buildDefaultStaticData(Session session) {
      Branch branch = new Branch(baseBranchName);
      Repository repository = new Repository(baseRepoName);
      repository.addBranch(branch);
      Landscape landscape = new Landscape(landscapeToken);
      landscape.addRepository(repository);
      Application application = new Application(baseAppName);
      application.setRootDirectory(new Directory(baseRepoName));
      landscape.addApplication(application);

      Directory currentDir = application.getRootDirectory();
      repository.setRootDirectory(currentDir);
      for (String dirName : baseDirNames) {
        Directory newDir = new Directory(dirName);
        currentDir.addSubdirectory(newDir);
        currentDir = newDir;
      }

      FileRevision file = new FileRevision(baseFileName);
      currentDir.addFileRevision(file);
      file.addFunction(new Function(baseFunctionName));
      file.setHash(baseFileHash);

      Commit commit = new Commit(baseCommitHash);
      repository.addCommit(commit);
      commit.addFileRevision(file);
      commit.setBranch(branch);
      landscape.addRepository(repository);

      session.save(List.of(landscape, application));
    }

    @BeforeEach
    void init() {
      baseRepoName = "myrepo";
      baseBranchName = "main";
      baseCommitHash = "commit1";
      baseDirNames = List.of("net", "explorviz", baseAppName);
      baseFileName = "MyClass.java";
      baseFilePath = ImmutableList.<String>builder().addAll(baseDirNames).add(baseFileName).build();
      baseFileHash = "1";
      baseFunctionName = "myMethod";

      buildDefaultStaticData(session);
    }

    TelemetryEntity.Builder baseEntityBuilder() {
      return TelemetryEntity.newBuilder()
          .setLandscapeTokenId(landscapeToken)
          .setCodeDescriptor(
              CodeDescriptor.newBuilder()
                  .setApplicationName(baseAppName)
                  .setFilePath(String.join("/", baseFilePath))
                  .setFunctionName(baseFunctionName));
    }

    @Test
    void testBaseStaticData() {
      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("repoName", baseRepoName);
      params.put("branchName", baseBranchName);
      params.put("repoRoot", baseRepoName);
      params.put("dirOne", baseDirNames.get(0));
      params.put("dirTwo", baseDirNames.get(1));
      params.put("dirThree", baseDirNames.get(2));
      params.put("funName", baseFunctionName);
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("commitHash", baseCommitHash);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (l:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(repo:Repository {name: $repoName})
                  -[:CONTAINS]->(:Commit {hash: $commitHash})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(:Function {name: $funName})

                MATCH (repo)-[:CONTAINS]->(:Branch {name: $branchName})<-[:BELONGS_TO]-(commit)

                MATCH (repo)
                  -[:HAS_ROOT]->(root:Directory {name: $repoRoot})
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(file)

                MATCH (l)-[:CONTAINS]->(:Application {name: $appName})-[:HAS_ROOT]->(root)
              } as exists;
              """,
              params);

      assertNotNull(databaseIsCorrect);
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
    void testPersistEntityWithoutCommitId() {
      List<String> filePath = new ArrayList<>(baseDirNames);
      Collections.addAll(filePath, baseFileName);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", filePath)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("repoRoot", filePath.get(0));
      params.put("dirOne", filePath.get(1));
      params.put("dirTwo", filePath.get(2));
      params.put("dirThree", filePath.get(3));
      params.put("fileName", baseFileName);
      params.put("funName", baseFunctionName);
      params.put("fileHash", baseFileHash);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory {name: $repoRoot})
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(d:Directory {name: $dirThree})
                  -[:CONTAINS]->(fileD:FileRevision {name: $fileName})
                  -[:CONTAINS]->(funD:Function {name: $funName})

                MATCH (d)
                  -[:CONTAINS]->(fileS:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(funS:Function {name: $funName})
                WHERE fileD.hash IS NULL AND funS <> funD
              } as exists;
              """,
              params);

      assertNotNull(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .repositories(1)
              .branches(1)
              .commits(1)
              .files(2)
              .applications(1)
              .directories(4)
              .functions(2)
              .build());
    }

    /**
     * If a commit hash is included in the entity and the corresponding commit, file and function
     * nodes exist, then no new nodes should be created.
     */
    @Test
    void testPersistEntityWithCommitAndStaticDataExists() {
      List<String> filePath = new ArrayList<>(baseDirNames);
      Collections.addAll(filePath, baseFileName);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", filePath)))
              .setGitCommitHash(baseCommitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", filePath.get(0));
      params.put("dirTwo", filePath.get(1));
      params.put("dirThree", filePath.get(2));
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("funName", baseFunctionName);
      params.put("commitHash", baseCommitHash);

      Commit foundCommit =
          session.queryForObject(
              Commit.class,
              """
              MATCH (:Landscape {tokenId: $landscapeToken})
                -[:CONTAINS]->(app:Application {name: $appName})
                -[:HAS_ROOT]->(:Directory)
                -[:CONTAINS]->(:Directory {name: $dirOne})
                -[:CONTAINS]->(:Directory {name: $dirTwo})
                -[:CONTAINS]->(:Directory {name: $dirThree})
                -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                -[:CONTAINS]->(:Function {name: $funName})
              MATCH (commit:Commit {hash: $commitHash})-[:CONTAINS]->(file)
              RETURN commit;
              """,
              params);

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
      assertNotNull(foundCommit);
    }

    @Test
    void testPersistEntityWithoutCommitIdForExistingFileRevision() {
      List<String> filePath = new ArrayList<>(baseDirNames);
      Collections.addAll(filePath, baseFileName);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", filePath)))
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", filePath.get(0));
      params.put("dirTwo", filePath.get(1));
      params.put("dirThree", filePath.get(2));
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("funName", baseFunctionName);
      params.put("commitHash", baseCommitHash);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(dir:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(fun:Function {name: $funName})

                MATCH (commit:Commit {hash: $commitHash})-[:CONTAINS]->(file)

                MATCH (dir)
                  -[:CONTAINS]->(fileDyn:FileRevision {name: $fileName})
                  -[:CONTAINS]->(funDyn:Function {name: $funName})
                WHERE NOT (commit)-[:CONTAINS]->(fileDyn)
                  AND NOT (file)-[:CONTAINS]->(funDyn)
                  AND NOT (fileDyn)-[:CONTAINS]->(fun)
                  AND fileDyn.hash IS NULL
                  AND file <> fileDyn
                  AND fun <> funDyn
              } as exists;
              """,
              params);

      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .repositories(1)
              .branches(1)
              .commits(1)
              .files(2)
              .applications(1)
              .directories(4)
              .functions(2)
              .build());
      assertNotNull(databaseIsCorrect);
    }

    @Test
    void testPersistEntityWithCommitIdForNonExistingFile() {
      String unknownFunctionName = "unknownFunction";
      String unknownFileName = "unknownFile.java";
      List<String> filePath = new ArrayList<>(baseDirNames);
      Collections.addAll(filePath, baseFileName);
      List<String> filePathTwo = new ArrayList<>(baseDirNames);
      Collections.addAll(filePathTwo, unknownFileName);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(unknownFunctionName)
                      .setFilePath(String.join("/", filePath)))
              .setGitCommitHash(baseCommitHash)
              .build();

      TelemetryEntity testEntityTwo =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(baseFunctionName)
                      .setFilePath(String.join("/", filePathTwo)))
              .setGitCommitHash(baseCommitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());
      telemetryConsumer.consume(testEntityTwo.toByteArray());

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", filePath.get(0));
      params.put("dirTwo", filePath.get(1));
      params.put("dirThree", filePath.get(2));
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("funName", baseFunctionName);
      params.put("commitHash", baseCommitHash);
      params.put("unknownFunName", unknownFunctionName);
      params.put("unknownFileName", unknownFileName);

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(dir:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(fun1:Function {name: $funName})

                MATCH (commit:Commit {hash: $commitHash})-[:CONTAINS]->(file)

                MATCH (dir)
                  -[:CONTAINS]->(file2:FileRevision {name: $fileName})
                  -[:CONTAINS]->(fun2:Function {name: $unknownFunName})

                MATCH (dir)
                  -[:CONTAINS]->(file3:FileRevision {name: $unknownFileName})
                  -[:CONTAINS]->(fun3:Function {name: $funName})
                WHERE NOT (commit)-[:CONTAINS]->(file2)
                  AND NOT (commit)-[:CONTAINS]->(file3)
                  AND fun1 <> fun2
                  AND fun1 <> fun3
                  AND fun2 <> fun3
                  AND file2.hash IS NULL
                  AND file <> file2
              } as exists;
              """,
              params);

      assertNotNull(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .repositories(1)
              .branches(1)
              .commits(1)
              .files(3)
              .applications(1)
              .directories(4)
              .functions(3)
              .build());
    }

    @Test
    void testPersistEntityWithPartOfFunctionPathAlreadyExisting() {
      List<String> filePath = new ArrayList<>(baseDirNames);
      String innerDir = "inner";
      String innerFileName = "Inner.java";
      String innerFunctionName = "innerFun";
      Collections.addAll(filePath, innerDir, innerFileName);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(innerFunctionName)
                      .setFilePath(String.join("/", filePath)))
              .setGitCommitHash(baseCommitHash)
              .build();

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("dirOne", filePath.get(0));
      params.put("dirTwo", filePath.get(1));
      params.put("dirThree", filePath.get(2));
      params.put("innerDir", innerDir);
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("funName", baseFunctionName);
      params.put("commitHash", baseCommitHash);
      params.put("innerFile", innerFileName);
      params.put("innerFunction", innerFunctionName);

      Boolean oldDatabaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(dir:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(fun:Function {name: $funName})

                MATCH (commit:Commit {hash: $commitHash})-[:CONTAINS]->(file)
                WHERE NOT (dir)-[:CONTAINS]->(:Directory {name: $innerDir})
                  AND NOT EXISTS { MATCH (:FileRevision {name: $innerFile}) }
                  AND NOT EXISTS { MATCH (:Function {name: $innerFunction}) }
              } as exists;
              """,
              params);

      telemetryConsumer.consume(testEntity.toByteArray());

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(app:Application {name: $appName})
                  -[:HAS_ROOT]->(:Directory)
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(dir:Directory {name: $dirThree})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(fun:Function {name: $funName})

                MATCH (commit:Commit {hash: $commitHash})-[:CONTAINS]->(file)

                MATCH (dir)
                  -[:CONTAINS]->(:Directory {name: $innerDir})
                  -[:CONTAINS]->(innerFile:FileRevision {name: $innerFile})
                  -[:CONTAINS]->(innerFun:Function {name: $innerFunction})
                WHERE NOT (commit)-[:CONTAINS]->(innerFile)
                  AND NOT (file)-[:CONTAINS]->(innerFun)
                  AND NOT (innerFile)-[:CONTAINS]->(fun)
                  AND innerFile.hash IS NULL
                  AND file <> innerFile
                  AND fun <> innerFun
              } as exists;
              """,
              params);

      assertNotNull(oldDatabaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .repositories(1)
              .branches(1)
              .commits(1)
              .files(2)
              .applications(1)
              .directories(5)
              .functions(2)
              .build());
      assertNotNull(databaseIsCorrect);
    }

    /*
    For "WithStaticData" the following two tests are enough to test the class path branch of
    saveEntity with commit hash, since the non-tested case all will fall back to the
    approach tested in "WithoutStaticData".
     */
    @Test
    void testPersistEntityWithClassPathWithAllDataAlreadyExisting() {
      String className = "A";
      String functionNameTwo = "function2";
      List<String> filePath = new ArrayList<>(baseDirNames);
      Collections.addAll(filePath, baseFileName);

      FileRevision file =
          session.queryForObject(
              FileRevision.class,
              """
              MATCH (f:FileRevision {name: $fileName, hash: $fileHash})
              RETURN f;
              """,
              Map.of("fileName", baseFileName, "fileHash", baseFileHash));

      Clazz clazz = new Clazz(className);
      clazz.addFunction(new Function(functionNameTwo));
      file.addClass(clazz);
      session.save(file);

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("repoName", baseRepoName);
      params.put("branchName", baseBranchName);
      params.put("repoRoot", baseRepoName);
      params.put("dirOne", baseDirNames.get(0));
      params.put("dirTwo", baseDirNames.get(1));
      params.put("dirThree", baseDirNames.get(2));
      params.put("funName", baseFunctionName);
      params.put("funNameTwo", functionNameTwo);
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("commitHash", baseCommitHash);
      params.put("className", className);

      Boolean preparedDatabaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (l:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(repo:Repository {name: $repoName})
                  -[:CONTAINS]->(:Commit {hash: $commitHash})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(:Function {name: $funName})

                MATCH (repo)
                  -[:CONTAINS]->(:Branch {name: $branchName})
                  <-[:BELONGS_TO]-(commit)

                MATCH (file)
                  -[:CONTAINS]->(:Clazz {name: $className})
                  -[:CONTAINS]->(:Function {name: $funNameTwo})

                MATCH (repo)
                  -[:HAS_ROOT]->(root:Directory {name: $repoRoot})
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(:Directory {name: $dirThree})
                  -[:CONTAINS]->(file)

                MATCH (l)-[:CONTAINS]->(:Application {name: $appName})-[:HAS_ROOT]->(root)
              } as exists;
              """,
              params);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", filePath))
                      .setClassName(className))
              .setGitCommitHash(baseCommitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(:Clazz {name: $className})
                  -[:CONTAINS]->(:Function {name: $funNameTwo})
              } as exists;
              """,
              params);

      assertTrue(preparedDatabaseIsCorrect);
      assertTrue(databaseIsCorrect);
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
              .functions(2)
              .classes(1)
              .build());
    }

    @Test
    void testPersistEntityWithClassPathWithoutAllDataAlreadyExisting() {
      String className = "A";
      String functionNameTwo = "function2";
      List<String> filePath = new ArrayList<>(baseDirNames);
      Collections.addAll(filePath, baseFileName);

      Map<String, Object> params = new HashMap<>();
      params.put("landscapeToken", landscapeToken);
      params.put("appName", baseAppName);
      params.put("repoName", baseRepoName);
      params.put("branchName", baseBranchName);
      params.put("repoRoot", baseRepoName);
      params.put("dirOne", baseDirNames.get(0));
      params.put("dirTwo", baseDirNames.get(1));
      params.put("dirThree", baseDirNames.get(2));
      params.put("funName", baseFunctionName);
      params.put("funNameTwo", functionNameTwo);
      params.put("fileName", baseFileName);
      params.put("fileHash", baseFileHash);
      params.put("commitHash", baseCommitHash);
      params.put("className", className);

      TelemetryEntity testEntity =
          baseEntityBuilder()
              .setCodeDescriptor(
                  CodeDescriptor.newBuilder()
                      .setApplicationName(baseAppName)
                      .setFunctionName(functionNameTwo)
                      .setFilePath(String.join("/", filePath))
                      .setClassName(className))
              .setGitCommitHash(baseCommitHash)
              .build();

      telemetryConsumer.consume(testEntity.toByteArray());

      Boolean databaseIsCorrect =
          session.queryForObject(
              Boolean.class,
              """
              RETURN EXISTS {
                MATCH (l:Landscape {tokenId: $landscapeToken})
                  -[:CONTAINS]->(repo:Repository {name: $repoName})
                  -[:CONTAINS]->(:Commit {hash: $commitHash})
                  -[:CONTAINS]->(file:FileRevision {name: $fileName, hash: $fileHash})
                  -[:CONTAINS]->(:Function {name: $funName})

                MATCH (repo)
                  -[:CONTAINS]->(:Branch {name: $branchName})
                  <-[:BELONGS_TO]-(commit)

                MATCH (repo)
                  -[:HAS_ROOT]->(root:Directory {name: $repoRoot})
                  -[:CONTAINS]->(:Directory {name: $dirOne})
                  -[:CONTAINS]->(:Directory {name: $dirTwo})
                  -[:CONTAINS]->(d:Directory {name: $dirThree})
                  -[:CONTAINS]->(file)

                MATCH (d)
                  -[:CONTAINS]->(file2:FileRevision {name: $fileName})
                  -[:CONTAINS]->(:Clazz {name: $className})
                  -[:CONTAINS]->(:Function {name: $funNameTwo})

                MATCH (l)-[:CONTAINS]->(:Application {name: $appName})-[:HAS_ROOT]->(root)
                WHERE file <> file2
              } as exists;
              """,
              params);

      assertTrue(databaseIsCorrect);
      assertNodeCounts(
          session,
          ExpectedCounts.builder()
              .landscapes(1)
              .repositories(1)
              .branches(1)
              .commits(1)
              .files(2)
              .applications(1)
              .directories(4)
              .functions(2)
              .classes(1)
              .build());
    }
  }
}
