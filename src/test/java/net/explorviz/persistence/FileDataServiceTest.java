package net.explorviz.persistence;

import static net.explorviz.persistence.util.TestUtils.assertNodeCounts;
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
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.proto.ClassData;
import net.explorviz.persistence.proto.ClassType;
import net.explorviz.persistence.proto.CommitData;
import net.explorviz.persistence.proto.CommitService;
import net.explorviz.persistence.proto.FieldData;
import net.explorviz.persistence.proto.FileData;
import net.explorviz.persistence.proto.FileDataService;
import net.explorviz.persistence.proto.FileIdentifier;
import net.explorviz.persistence.proto.FunctionData;
import net.explorviz.persistence.proto.Language;
import net.explorviz.persistence.proto.ParameterData;
import net.explorviz.persistence.proto.StateDataRequest;
import net.explorviz.persistence.proto.StateDataService;
import net.explorviz.persistence.util.ExpectedCounts;
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
    session.purgeDatabase();

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
            MATCH (cc)-[:INHERITS]->(cs)

            WHERE NOT EXISTS { MATCH (fs)-[:CONTAINS]->(fun) }
              AND NOT EXISTS { MATCH (fs)-[:CONTAINS]->(ci) }
              AND NOT EXISTS { MATCH (cc)-[:CONTAINS]->(fun) }
              AND NOT EXISTS { MATCH (cc)-[:CONTAINS]->(ci) }
              AND NOT EXISTS { MATCH (cc)-[:INHERITS]->(ci) }
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
            MATCH (cc)-[:INHERITS]->(cs)
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
        "No corresponding file was sent before in CommitData.", ex.getStatus().getDescription());
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
  void testPersistFileThrowsWithInvalidSuperclassNameFormat() {
    String commitHash = "commit1";
    String superclassName = "Class1";
    String fileNameSuper = superclassName + ".java";
    String superclassFqn = "src." + fileNameSuper + "." + superclassName;
    String superclassFqnTwo = "src::" + fileNameSuper + "::" + superclassName;
    String filePathSuper = "src/" + fileNameSuper;
    String fileHashSuper = "1";
    String className = "Class2";
    String fileNameClass = className + ".java";
    String filePathClass = "src/" + fileNameClass;
    String fileHashClass = "2";

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

    Map<String, Object> params = new HashMap<>();
    params.put("landscapeToken", landscapeToken);
    params.put("repoName", repoName);
    params.put("branchName", branchName);
    params.put("commitHash", commitHash);
    params.put("fileNameSuper", fileNameSuper);
    params.put("fileHashSuper", fileHashSuper);
    params.put("fileNameClass", fileNameClass);
    params.put("fileHashClass", fileHashClass);
    params.put("srcDir", "src");

    String dbQuery =
        """
        RETURN EXISTS {
        MATCH (:Landscape {tokenId: $landscapeToken})
          -[:CONTAINS]->(r:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit {hash: $commitHash})
          -[:CONTAINS]->(:FileRevision {hash: $fileHashSuper, name: $fileNameSuper})
          <-[:CONTAINS]-(src:Directory {name: $srcDir})
          <-[:CONTAINS]-(:Directory {name: $repoName})

        MATCH (c)
          -[:CONTAINS]->(:FileRevision {hash: $fileHashClass, name: $fileNameClass})
          <-[:CONTAINS]-(src)

        MATCH (r)
          -[:CONTAINS]->(:Branch {name: $branchName})
          <-[:BELONGS_TO]-(c)
        } AS exists
        """;

    Boolean databaseCorrectBeforePersistFile =
        session.queryForObject(Boolean.class, dbQuery, params);

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

    ClassData classDataTwo =
        ClassData.newBuilder()
            .setName(className)
            .setType(ClassType.CLASS)
            .addAllModifiers(List.of())
            .addAllImplementedInterfaces(List.of())
            .addAllSuperclasses(List.of(superclassFqnTwo))
            .addAllAnnotations(List.of())
            .addAllFields(List.of())
            .addAllInnerClasses(List.of())
            .addAllFunctions(List.of())
            .addAllEnumValues(List.of())
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

    FileData fileDataClassTwo =
        FileData.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setFileHash(fileHashClass)
            .setFilePath(filePathClass)
            .setLanguage(Language.JAVA)
            .addAllImportNames(List.of("Superclass"))
            .addAllClasses(List.of(classDataTwo))
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
                    .persistFile(fileDataClass)
                    .await()
                    .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS)));

    Boolean databaseCorrectAfterPersistFileOne =
        session.queryForObject(Boolean.class, dbQuery, params);

    Boolean fileOneHasFileData =
        session.queryForObject(
            Boolean.class,
            """
              MATCH (f:FileRevision {hash: $fileHashSuper, name: $fileNameSuper})
              RETURN f.hasFileData;
            """,
            params);

    StatusRuntimeException exTwo =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                fileDataService
                    .persistFile(fileDataClassTwo)
                    .await()
                    .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS)));

    Boolean databaseCorrectAfterPersistFileTwo =
        session.queryForObject(Boolean.class, dbQuery, params);

    Boolean fileTwoHasFileData =
        session.queryForObject(
            Boolean.class,
            """
              MATCH (f:FileRevision {hash: $fileHashClass, name: $fileNameClass})
              RETURN f.hasFileData;
            """,
            params);

    assertTrue(databaseCorrectBeforePersistFile);
    assertEquals(Status.INVALID_ARGUMENT.getCode(), ex.getStatus().getCode());
    assertEquals(
        "Format of super class invalid:\n"
            + "   Expected format: path/to/file/file.extension::class \n"
            + "   Actual value: "
            + superclassFqn,
        ex.getStatus().getDescription());
    assertTrue(databaseCorrectAfterPersistFileOne);
    assertFalse(fileOneHasFileData);

    assertEquals(Status.INVALID_ARGUMENT.getCode(), exTwo.getStatus().getCode());
    assertEquals(
        "Format of super class invalid:\n"
            + "   Expected format: path/to/file/file.extension::class \n"
            + "   Actual value: "
            + superclassFqnTwo,
        exTwo.getStatus().getDescription());
    assertTrue(databaseCorrectAfterPersistFileTwo);
    assertFalse(fileTwoHasFileData);

    assertNodeCounts(
        session,
        ExpectedCounts.builder()
            .landscapes(1)
            .repositories(1)
            .branches(1)
            .directories(2)
            .commits(1)
            .files(2)
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

    Boolean inheritsFromAnalyzedParent =
        session.queryForObject(
            Boolean.class,
            """
            MATCH (:FileRevision {hash: $childHash})-[:CONTAINS]->(:Clazz {name: $childName})
              -[:INHERITS]->(:Clazz {name: $parentName})
            RETURN true;
            """,
            Map.of("childHash", childHash, "childName", childName, "parentName", parentName));

    assertTrue(inheritsFromAnalyzedParent);
  }
}
