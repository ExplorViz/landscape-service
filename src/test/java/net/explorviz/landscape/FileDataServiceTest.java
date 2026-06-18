package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.assertNodeCounts;
import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.proto.ClassData;
import net.explorviz.landscape.proto.ClassType;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.CommitService;
import net.explorviz.landscape.proto.FieldData;
import net.explorviz.landscape.proto.FileData;
import net.explorviz.landscape.proto.FileDataService;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.proto.FunctionData;
import net.explorviz.landscape.proto.Language;
import net.explorviz.landscape.proto.ParameterData;
import net.explorviz.landscape.proto.StateDataRequest;
import net.explorviz.landscape.proto.StateDataService;
import net.explorviz.landscape.util.ExpectedCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class FileDataServiceTest {

  private static final long GRPC_AWAIT_SECONDS = 5;

  @GrpcClient CommitService commitService;

  @GrpcClient StateDataService stateDataService;

  @GrpcClient FileDataService fileDataService;

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
  void testPersistFileWithCorrectMetrics() {
    String commitHash = "commit1";
    String filePath = "src/File1.java";
    String fileHash = "1";

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

    FileRevision file =
        session.queryForObject(
            FileRevision.class,
            """
            MATCH (f:FileRevision {hash: $fileHash})
            RETURN f;
            """,
            Map.of("fileHash", fileHash));

    for (String k : file.getMetrics().keySet()) {
      assertEquals(testMap.get(k), file.getMetrics().get(k));
    }
  }

  @Test
  void testPersistFileDuplicateFileOnDifferentPaths() {
    String commitHash = "commit1";
    String filePathOne = "src/File1.java";
    String filePathTwo = "src/hollandaise/File1.java";
    String fileHash = "1";

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
                        .setFilePath(filePathOne)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHash)
                        .setFilePath(filePathTwo)
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
            .setFilePath(filePathTwo)
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

    FileData fileDataTwo =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHash)
            .setFilePath(filePathTwo)
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
    fileDataService.persistFile(fileDataTwo).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Iterable<FileRevision> files =
        session.query(
            FileRevision.class,
            """
            MATCH (f:FileRevision {hash: $fileHash})
            RETURN f;
            """,
            Map.of("fileHash", fileHash));

    List<FileRevision> fileList = new ArrayList<>();
    files.forEach(fileList::add);

    assertEquals(2, fileList.size());
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(3)
            .files(2)
            .commits(1)
            .build());
  }

  @Test
  void testPersistFileCorrectlyCreatesClazzNode() {
    String commitHash = "commit1";
    String superclassName = "Class1";
    String fileNameSuper = superclassName + ".java";
    String superclassFqn = "src/" + fileNameSuper + "::" + superclassName;
    String filePathSuper = "src/" + fileNameSuper;
    String fileHashSuper = "1";
    String className = "Class2";
    String fileNameClass = className + ".java";
    String filePathClass = "src/" + fileNameClass;
    String fileHashClass = "2";
    String innerclassName = "Inner";
    String fieldNameSuper = "field1";
    String fieldNameClass = "field2";
    String fieldType = "String";
    String functionNameSuper = "superFunction";
    String functionReturnType = "String";

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
                        .setFileHash(fileHashSuper)
                        .setFilePath(filePathSuper)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashClass)
                        .setFilePath(filePathClass)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    FieldData fieldDataSuper =
        FieldData.newBuilder()
            .setName(fieldNameSuper)
            .setType(fieldType)
            .addAllModifiers(List.of())
            .build();

    FieldData fieldDataClass =
        FieldData.newBuilder()
            .setName(fieldNameClass)
            .setType(fieldType)
            .addAllModifiers(List.of())
            .build();

    FunctionData functionDataSuper =
        FunctionData.newBuilder()
            .setName(functionNameSuper)
            .setReturnType(functionReturnType)
            .setIsConstructor(false)
            .addAllAnnotations(List.of())
            .addAllModifiers(List.of())
            .addAllParameters(List.of())
            .addAllOutgoingMethodCalls(List.of())
            .setStartLine(4)
            .setEndLine(8)
            .build();

    ClassData innerclassData =
        ClassData.newBuilder()
            .setName(innerclassName)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of())
            .addAllAnnotations(List.of())
            .addAllFields(List.of())
            .addAllInnerClasses(List.of())
            .addAllFunctions(List.of())
            .addAllEnumValues(List.of())
            .build();

    ClassData superclassData =
        ClassData.newBuilder()
            .setName(superclassName)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of())
            .addAllAnnotations(List.of())
            .addAllFields(List.of(fieldDataSuper))
            .addAllInnerClasses(List.of(innerclassData))
            .addAllFunctions(List.of(functionDataSuper))
            .addAllEnumValues(List.of())
            .build();

    ClassData classData =
        ClassData.newBuilder()
            .setName(className)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of(superclassFqn))
            .addAllAnnotations(List.of())
            .addAllFields(List.of(fieldDataClass))
            .addAllInnerClasses(List.of())
            .addAllFunctions(List.of())
            .addAllEnumValues(List.of())
            .build();

    FileData fileDataSuper =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashSuper)
            .setFilePath(filePathSuper)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Test"))
            .addAllClasses(List.of(superclassData))
            .addAllFunctions(List.of())
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    FileData fileDataClass =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashClass)
            .setFilePath(filePathClass)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Superclass"))
            .addAllClasses(List.of(classData))
            .addAllFunctions(List.of())
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    fileDataService
        .persistFile(fileDataSuper)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
    fileDataService
        .persistFile(fileDataClass)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("commitHash", commitHash);
    params.put("fileNameSuper", fileNameSuper);
    params.put("fileHashSuper", fileHashSuper);
    params.put("classNameSuper", superclassName);
    params.put("classType", ClassType.CLASS);
    params.put("fieldNameSuper", fieldNameSuper);
    params.put("fieldType", fieldType);
    params.put("functionName", functionNameSuper);
    params.put("functionReturnType", functionReturnType);
    params.put("fileNameClass", fileNameClass);
    params.put("fileHashClass", fileHashClass);
    params.put("classNameClass", className);
    params.put("fieldNameClass", fieldNameClass);
    params.put("innerClassName", innerclassName);

    params.put("superclassFqn", superclassFqn);
    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit {hash: $commitHash})

            MATCH (c)-[:CONTAINS]->(fs:FileRevision {name: $fileNameSuper, hash: $fileHashSuper})
              -[:CONTAINS]->(cs:Clazz {name: $classNameSuper, type: $classType})
            MATCH (cs)-[:CONTAINS]->(:Field {name: $fieldNameSuper, type: $fieldType})
            MATCH (cs)-[:CONTAINS]->(fun:Function {name: $functionName, returnType: $functionReturnType})
            MATCH (cs)-[:CONTAINS]->(ci:Clazz {name: $innerClassName, type: $classType})

            MATCH (c)-[:CONTAINS]->(fc:FileRevision {name: $fileNameClass, hash: $fileHashClass})
              -[:CONTAINS]->(cc:Clazz {name: $classNameClass, type: $classType})
            MATCH (cc)-[:CONTAINS]->(:Field {name: $fieldNameClass, type: $fieldType})
            WHERE $superclassFqn IN cc.superclassFqns
              AND NOT EXISTS { MATCH (fs)-[:CONTAINS]->(fun) }
              AND NOT EXISTS { MATCH (fs)-[:CONTAINS]->(ci) }
              AND NOT EXISTS { MATCH (cc)-[:CONTAINS]->(fun) }
              AND NOT EXISTS { MATCH (cc)-[:CONTAINS]->(ci) }
            } AS exists
            """,
            params);

    assertTrue(databaseIsCorrect);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .files(2)
            .commits(1)
            .classes(3)
            .fields(2)
            .functions(1)
            .build());
  }

  @Test
  void testPersistFileInheritingClazzBeforeSuperClazz() {
    String commitHash = "commit1";
    String superclassName = "Class1";
    String fileNameSuper = superclassName + ".java";
    String superclassFqn = "src/" + fileNameSuper + "::" + superclassName;
    String filePathSuper = "src/" + fileNameSuper;
    String fileHashSuper = "1";
    String className = "Class2";
    String fileNameClass = className + ".java";
    String filePathClass = "src/" + fileNameClass;
    String fileHashClass = "2";
    String innerclassName = "Inner";
    String fieldNameSuper = "field1";
    String fieldType = "String";
    String functionNameSuper = "superFunction";
    String functionReturnType = "String";

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
                        .setFileHash(fileHashSuper)
                        .setFilePath(filePathSuper)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashClass)
                        .setFilePath(filePathClass)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    FieldData fieldDataSuper =
        FieldData.newBuilder()
            .setName(fieldNameSuper)
            .setType(fieldType)
            .addAllModifiers(List.of())
            .build();

    FunctionData functionDataSuper =
        FunctionData.newBuilder()
            .setName(functionNameSuper)
            .setReturnType(functionReturnType)
            .setIsConstructor(false)
            .addAllAnnotations(List.of())
            .addAllModifiers(List.of())
            .addAllParameters(List.of())
            .addAllOutgoingMethodCalls(List.of())
            .setStartLine(4)
            .setEndLine(8)
            .build();

    ClassData innerclassData =
        ClassData.newBuilder()
            .setName(innerclassName)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of())
            .addAllAnnotations(List.of())
            .addAllFields(List.of())
            .addAllInnerClasses(List.of())
            .addAllFunctions(List.of())
            .addAllEnumValues(List.of())
            .build();

    ClassData superclassData =
        ClassData.newBuilder()
            .setName(superclassName)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of())
            .addAllAnnotations(List.of())
            .addAllFields(List.of(fieldDataSuper))
            .addAllInnerClasses(List.of(innerclassData))
            .addAllFunctions(List.of(functionDataSuper))
            .addAllEnumValues(List.of())
            .build();

    ClassData classData =
        ClassData.newBuilder()
            .setName(className)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of(superclassFqn))
            .addAllAnnotations(List.of())
            .addAllFields(List.of())
            .addAllInnerClasses(List.of())
            .addAllFunctions(List.of())
            .addAllEnumValues(List.of())
            .build();

    FileData fileDataSuper =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashSuper)
            .setFilePath(filePathSuper)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Test"))
            .addAllClasses(List.of(superclassData))
            .addAllFunctions(List.of())
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    FileData fileDataClass =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashClass)
            .setFilePath(filePathClass)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Superclass"))
            .addAllClasses(List.of(classData))
            .addAllFunctions(List.of())
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    fileDataService
        .persistFile(fileDataClass)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
    fileDataService
        .persistFile(fileDataSuper)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("commitHash", commitHash);
    params.put("fileNameSuper", fileNameSuper);
    params.put("fileHashSuper", fileHashSuper);
    params.put("classNameSuper", superclassName);
    params.put("classType", ClassType.CLASS);
    params.put("fieldNameSuper", fieldNameSuper);
    params.put("fieldType", fieldType);
    params.put("functionName", functionNameSuper);
    params.put("functionReturnType", functionReturnType);
    params.put("fileNameClass", fileNameClass);
    params.put("fileHashClass", fileHashClass);
    params.put("classNameClass", className);
    params.put("innerClassName", innerclassName);

    params.put("superclassFqn", superclassFqn);
    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit {hash: $commitHash})

            MATCH (c)-[:CONTAINS]->(fs:FileRevision {name: $fileNameSuper, hash: $fileHashSuper})
              -[:CONTAINS]->(cs:Clazz {name: $classNameSuper, type: $classType})
            MATCH (cs)-[:CONTAINS]->(:Field {name: $fieldNameSuper, type: $fieldType})
            MATCH (cs)-[:CONTAINS]->(fun:Function {name: $functionName, returnType: $functionReturnType})
            MATCH (cs)-[:CONTAINS]->(ci:Clazz {name: $innerClassName, type: $classType})

            MATCH (c)-[:CONTAINS]->(fc:FileRevision {name: $fileNameClass, hash: $fileHashClass})
              -[:CONTAINS]->(cc:Clazz {name: $classNameClass, type: $classType})
            WHERE $superclassFqn IN cc.superclassFqns
            } AS exists
            """,
            params);

    assertTrue(databaseIsCorrect);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .files(2)
            .commits(1)
            .classes(3)
            .fields(1)
            .functions(1)
            .build());
  }

  @Test
  void testPersistFileCorrectlyCreatesFunctionNodes() {
    String commitHash = "commit1";
    String fileNameOne = "file1.java";
    String filePathOne = "src/" + fileNameOne;
    String fileNameTwo = "file2.java";
    String filePathTwo = "src/" + fileNameTwo;
    String fileHashOne = "1";
    String fileHashTwo = "2";
    String functionName = "function";
    String functionReturnType = "String";
    String parameterNameOne = "param1";
    String parameterNameTwo = "param2";
    String parameterTypeOne = "int";
    String parameterTypeTwo = "boolean";

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

    ParameterData paramOne =
        ParameterData.newBuilder()
            .setName(parameterNameOne)
            .setType(parameterTypeOne)
            .addAllModifiers(List.of())
            .build();

    ParameterData paramTwo =
        ParameterData.newBuilder()
            .setName(parameterNameTwo)
            .setType(parameterTypeTwo)
            .addAllModifiers(List.of())
            .build();

    FunctionData functionDataOne =
        FunctionData.newBuilder()
            .setName(functionName)
            .setReturnType(functionReturnType)
            .setIsConstructor(false)
            .addAllAnnotations(List.of())
            .addAllModifiers(List.of())
            .addAllParameters(List.of(paramOne))
            .addAllOutgoingMethodCalls(List.of())
            .setStartLine(1)
            .setEndLine(10)
            .build();

    FunctionData functionDataTwo =
        FunctionData.newBuilder()
            .setName(functionName)
            .setReturnType(functionReturnType)
            .setIsConstructor(false)
            .addAllAnnotations(List.of())
            .addAllModifiers(List.of())
            .addAllParameters(List.of(paramOne, paramTwo))
            .addAllOutgoingMethodCalls(List.of())
            .setStartLine(1)
            .setEndLine(24)
            .build();

    FileData fileDataOne =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashOne)
            .setFilePath(filePathOne)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Test"))
            .addAllClasses(List.of())
            .addAllFunctions(List.of(functionDataOne))
            .setLastEditor("Testi")
            .setAddedLines(10)
            .setModifiedLines(0)
            .setDeletedLines(0)
            .build();

    FileData fileDataTwo =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashTwo)
            .setFilePath(filePathTwo)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of())
            .addAllClasses(List.of())
            .addAllFunctions(List.of(functionDataTwo))
            .setLastEditor("Testi")
            .setAddedLines(24)
            .setModifiedLines(0)
            .setDeletedLines(0)
            .build();

    fileDataService.persistFile(fileDataOne).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
    fileDataService.persistFile(fileDataTwo).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("commitHash", commitHash);
    params.put("fileNameOne", fileNameOne);
    params.put("fileHashOne", fileHashOne);
    params.put("funName", functionName);
    params.put("returnType", functionReturnType);
    params.put("paramNameOne", parameterNameOne);
    params.put("paramTypeOne", parameterTypeOne);
    params.put("fileNameTwo", fileNameTwo);
    params.put("fileHashTwo", fileHashTwo);
    params.put("paramNameTwo", parameterNameTwo);
    params.put("paramTypeTwo", parameterTypeTwo);

    Boolean databaseIsCorrect =
        session.queryForObject(
            Boolean.class,
            """
            RETURN EXISTS {
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit {hash: $commitHash})

            MATCH (c)-[:CONTAINS]->(f1:FileRevision {name: $fileNameOne, hash: $fileHashOne})
              -[:CONTAINS]->(fun1:Function {name: $funName, returnType: $returnType})
            MATCH (fun1)-[:CONTAINS]->(param1:Parameter {name: $paramNameOne, type: $paramTypeOne})

            MATCH (c)-[:CONTAINS]->(f2:FileRevision {name: $fileNameTwo, hash: $fileHashTwo})
              -[:CONTAINS]->(fun2:Function {name: $funName, returnType: $returnType})
            MATCH (fun2)-[:CONTAINS]->(param2:Parameter {name: $paramNameOne, type: $paramTypeOne})
            MATCH (fun2)-[:CONTAINS]->(param3:Parameter {name: $paramNameTwo, type: $paramTypeTwo})

            WHERE NOT EXISTS { MATCH (f2)-[:CONTAINS]->(fun1) }
              AND NOT EXISTS { MATCH (f1)-[:CONTAINS]->(fun2) }
              AND NOT EXISTS { MATCH (fun1)-[:CONTAINS]->(param2) }
              AND NOT EXISTS { MATCH (fun1)-[:CONTAINS]->(param3) }
              AND NOT EXISTS { MATCH (fun2)-[:CONTAINS]->(param1) }
              AND param1 <> param2
            } AS exists
            """,
            params);

    assertTrue(databaseIsCorrect);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .files(2)
            .commits(1)
            .parameters(3)
            .functions(2)
            .build());
  }

  @Test
  void testPersistFileThrowsForUnknownFile() {
    String commitHash = "commit1";
    String fileNameOne = "File1.java";
    String filePathOne = "src/" + fileNameOne;
    String filePathTwo = "src/File2.java";
    String fileHashOne = "1";
    String fileHashTwo = "2";

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
                        .setFileHash(fileHashOne)
                        .setFilePath(filePathOne)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("branchName", branchName);
    params.put("commitHash", commitHash);
    params.put("fileName", fileNameOne);
    params.put("fileHash", fileHashOne);
    params.put("srcDir", "src");

    String dbQuery =
        """
        RETURN EXISTS {
        MATCH (:Landscape {tokenId: $landscapeToken})
          -[:CONTAINS]->(r:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit {hash: $commitHash})
          -[:CONTAINS]->(:FileRevision {hash: $fileHash, name: $fileName})
          <-[:CONTAINS]-(:Directory {name: $srcDir})
          <-[:CONTAINS]-(:Directory {name: $repoName})

        MATCH (r)
          -[:CONTAINS]->(:Branch {name: $branchName})
          <-[:BELONGS_TO]-(c)
        } AS exists
        """;

    Boolean databaseCorrectBeforePersistFile =
        session.queryForObject(Boolean.class, dbQuery, params);

    FileData fileDataTwo =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashTwo)
            .setFilePath(filePathTwo)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Test"))
            .addAllClasses(List.of())
            .addAllFunctions(List.of())
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                fileDataService
                    .persistFile(fileDataTwo)
                    .await()
                    .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS)));

    Boolean databaseCorrectAfterPersistFile =
        session.queryForObject(Boolean.class, dbQuery, params);

    Boolean fileHasFileData =
        session.queryForObject(
            Boolean.class,
            """
              MATCH (f:FileRevision {hash: $fileHash, name: $fileName})
              RETURN f.hasFileData;
            """,
            params);

    assertTrue(databaseCorrectBeforePersistFile);
    assertEquals(Status.FAILED_PRECONDITION.getCode(), ex.getStatus().getCode());
    assertEquals(
        "No corresponding file was sent before in CommitData: src/File2.java",
        ex.getStatus().getDescription());
    assertTrue(databaseCorrectAfterPersistFile);
    assertFalse(fileHasFileData);
    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .commits(1)
            .files(1)
            .build());
  }

  @Test
  void testPersistFileSuperclassFqnStoredVerbatimOnClazz() {
    String commitHash = "commit1";
    String superclassName = "Class1";
    String fileNameSuper = superclassName + ".java";
    String filePathSuper = "src/" + fileNameSuper;
    String fileHashSuper = "1";
    String className = "Class2";
    String fileNameClass = className + ".java";
    String filePathClass = "src/" + fileNameClass;
    String fileHashClass = "2";
    // FQN using a dot-separated path instead of the conventional "/" separator; stored verbatim.
    String arbitraryFqn = "src.some.pkg." + superclassName;

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
                        .setFileHash(fileHashSuper)
                        .setFilePath(filePathSuper)
                        .build(),
                    FileIdentifier.newBuilder()
                        .setFileHash(fileHashClass)
                        .setFilePath(filePathClass)
                        .build()))
            .build();

    commitService
        .persistCommit(commitDataOne)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    ClassData classData =
        ClassData.newBuilder()
            .setName(className)
            .setType(ClassType.CLASS)
            .addAllSuperclasses(List.of(arbitraryFqn))
            .build();

    FileData fileDataClass =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashClass)
            .setFilePath(filePathClass)
            .setLanguage(Language.JAVA)
            .addAllClasses(List.of(classData))
            .setLastEditor("Testi")
            .setAddedLines(1)
            .setModifiedLines(1)
            .setDeletedLines(0)
            .build();

    fileDataService
        .persistFile(fileDataClass)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Boolean fqnStoredVerbatim =
        session.queryForObject(
            Boolean.class,
            """
            MATCH (cl:Clazz {name: $className})
            RETURN $arbitraryFqn IN cl.superclassFqns;
            """,
            Map.of("className", className, "arbitraryFqn", arbitraryFqn));

    assertTrue(fqnStoredVerbatim);

    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .commits(1)
            .files(2)
            .classes(1)
            .build());
  }

  @Test
  void testPersistFileWithSuperclassAcrossMultipleCommits() {
    String commitHashOne = "commit1";
    String commitHashTwo = "commit2";
    String parentName = "Parent";
    String parentPath = "src/" + parentName + ".java";
    String parentHashOne = "parent-hash-1";
    String parentHashTwo = "parent-hash-2";
    String childName = "Child";
    String childPath = "src/" + childName + ".java";
    String childHash = "child-hash";
    String parentFqn = parentPath + "::" + parentName;

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(commitHashOne)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
                .addAddedFiles(
                    FileIdentifier.newBuilder()
                        .setFileHash(parentHashOne)
                        .setFilePath(parentPath)
                        .build())
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    fileDataService
        .persistFile(
            FileData.newBuilder()
                .setLandscapeToken(landscapeToken)
                .setRepositoryName(repoName)
                .setFileHash(parentHashOne)
                .setFilePath(parentPath)
                .setLanguage(Language.JAVA)
                .addClasses(
                    ClassData.newBuilder().setName(parentName).setType(ClassType.CLASS).build())
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    commitService
        .persistCommit(
            CommitData.newBuilder()
                .setCommitId(commitHashTwo)
                .setParentCommitId(commitHashOne)
                .setRepositoryName(repoName)
                .setBranchName(branchName)
                .setLandscapeToken(landscapeToken)
                .setAuthorDate(Timestamp.newBuilder().setSeconds(2).setNanos(100).build())
                .addModifiedFiles(
                    FileIdentifier.newBuilder()
                        .setFileHash(parentHashTwo)
                        .setFilePath(parentPath)
                        .build())
                .addAddedFiles(
                    FileIdentifier.newBuilder()
                        .setFileHash(childHash)
                        .setFilePath(childPath)
                        .build())
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    fileDataService
        .persistFile(
            FileData.newBuilder()
                .setLandscapeToken(landscapeToken)
                .setRepositoryName(repoName)
                .setFileHash(childHash)
                .setFilePath(childPath)
                .setLanguage(Language.JAVA)
                .addClasses(
                    ClassData.newBuilder()
                        .setName(childName)
                        .setType(ClassType.CLASS)
                        .addSuperclasses(parentFqn)
                        .build())
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Boolean superclassFqnStored =
        session.queryForObject(
            Boolean.class,
            """
            MATCH (:FileRevision {hash: $childHash})-[:CONTAINS]->(cl:Clazz {name: $childName})
            RETURN $parentFqn IN cl.superclassFqns;
            """,
            Map.of("childHash", childHash, "childName", childName, "parentFqn", parentFqn));

    assertTrue(superclassFqnStored);
  }

  @Test
  void testPersistFilesInBatch() {
    final String commitHash = "batchcommit1";
    final String filePath1 = "src/Alpha.java";
    final String fileHash1 = "alpha1";
    final String filePath2 = "src/sub/Beta.java";
    final String fileHash2 = "beta1";

    final CommitData commitData =
        CommitData.newBuilder()
            .setCommitId(commitHash)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .setLandscapeToken(landscapeToken)
            .setAuthorDate(Timestamp.newBuilder().setSeconds(1).setNanos(100).build())
            .addAddedFiles(
                FileIdentifier.newBuilder().setFileHash(fileHash1).setFilePath(filePath1).build())
            .addAddedFiles(
                FileIdentifier.newBuilder().setFileHash(fileHash2).setFilePath(filePath2).build())
            .build();

    commitService.persistCommit(commitData).await().atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    final FileData fileData1 =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHash1)
            .setFilePath(filePath1)
            .setLanguage(Language.JAVA)
            .setLastEditor("alice")
            .setAddedLines(10)
            .addClasses(
                ClassData.newBuilder()
                    .setName("Alpha")
                    .setType(ClassType.CLASS)
                    .addFields(FieldData.newBuilder().setName("count").setType("int").build())
                    .addFunctions(
                        FunctionData.newBuilder()
                            .setName("getCount")
                            .setReturnType("int")
                            .addParameters(
                                ParameterData.newBuilder().setName("x").setType("int").build())
                            .build())
                    .build())
            .build();

    final FileData fileData2 =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHash2)
            .setFilePath(filePath2)
            .setLanguage(Language.KOTLIN)
            .setLastEditor("bob")
            .addClasses(
                ClassData.newBuilder()
                    .setName("Beta")
                    .setType(ClassType.INTERFACE)
                    .addInnerClasses(
                        ClassData.newBuilder()
                            .setName("BetaInner")
                            .setType(ClassType.CLASS)
                            .build())
                    .build())
            .build();

    fileDataService
        .persistFiles(io.smallrye.mutiny.Multi.createFrom().items(fileData1, fileData2))
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    // Both FileRevisions should have hasFileData = true
    final Iterable<Map<String, Object>> rawResults =
        session
            .query(
                """
                MATCH (f:FileRevision)
                WHERE f.hash IN $hashes
                RETURN f.hash AS hash, f.hasFileData AS hasFileData, f.lastEditor AS editor
                """,
                Map.of("hashes", List.of(fileHash1, fileHash2)))
            .queryResults();
    final List<Map<String, Object>> results = new ArrayList<>();
    rawResults.forEach(results::add);

    assertEquals(2, results.size());
    results.forEach(row -> assertTrue((Boolean) row.get("hasFileData")));

    // Clazz nodes created for both files (including inner classes at any depth)
    final Long clazzCount =
        session.queryForObject(
            Long.class,
            """
            MATCH (f:FileRevision)-[:CONTAINS*1..]->(cl:Clazz)
            WHERE f.hash IN $hashes
            RETURN count(cl)
            """,
            Map.of("hashes", List.of(fileHash1, fileHash2)));
    // Alpha has 1, Beta has 1 top-level + 1 inner = 3 total
    assertEquals(3L, clazzCount);

    // Field created under Alpha
    final Long fieldCount =
        session.queryForObject(
            Long.class,
            """
            MATCH (:FileRevision {hash: $hash})-[:CONTAINS]->(cl:Clazz {name: 'Alpha'})
                  -[:CONTAINS]->(fi:Field {name: 'count'})
            RETURN count(fi)
            """,
            Map.of("hash", fileHash1));
    assertEquals(1L, fieldCount);

    // Function and its parameter created under Alpha
    final Long paramCount =
        session.queryForObject(
            Long.class,
            """
            MATCH (:FileRevision {hash: $hash})-[:CONTAINS]->(cl:Clazz {name: 'Alpha'})
                  -[:CONTAINS]->(func:Function {name: 'getCount'})
                  -[:CONTAINS]->(p:Parameter {name: 'x'})
            RETURN count(p)
            """,
            Map.of("hash", fileHash1));
    assertEquals(1L, paramCount);
  }
}
