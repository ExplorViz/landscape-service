package net.explorviz.landscape.repository;

import com.google.common.collect.ObjectArrays;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class FunctionRepository {

  private static final String FIND_BY_FQN_AND_LANDSCAPE_TOKEN_STATEMENT =
      """
      MATCH (l:Landscape {tokenId: $tokenId})
      MATCH (func:Function {name: $name})
      WHERE (l)
        -[:CONTAINS]->(:Trace)
        -[:CONTAINS]->(:Span)
        -[:REPRESENTS]->(func)
      MATCH (l)
        -[:CONTAINS]->(:Application {name: $appName})
        -[:HAS_ROOT]->(appRoot:Directory)
      MATCH p = (fqnRoot:Directory|FileRevision)-[:CONTAINS]->*(file:FileRevision)
      WHERE
        (appRoot)-[:CONTAINS]->(fqnRoot) AND
        (file)-[:CONTAINS]->(func) AND
        NOT (:Commit)-[:CONTAINS]->(file) AND
        all(j IN range(0, length(p)) WHERE nodes(p)[j].name = $pathSegments[j]) AND
        size(nodes(p)) = size($pathSegments)
      RETURN func;
      """;

  private static final String FIND_BY_FQN_AND_LANDSCAPE_TOKEN_AND_COMMIT_HASH_STATEMENT =
      """
      MATCH (l:Landscape {tokenId: $tokenId})
        -[:CONTAINS]->(:Application {name: $appName})
        -[:HAS_ROOT]->(appRoot:Directory)
      WHERE (l)
        -[:CONTAINS]->(:Repository)
        -[:HAS_ROOT]->(:Directory)
        -[:CONTAINS*0..]->(appRoot:Directory)
      MATCH p = (fqnRoot:Directory|FileRevision)
        -[:CONTAINS]->*(file:FileRevision)
        -[:CONTAINS]->(func:Function)
      WHERE
        (appRoot)-[:CONTAINS]->(fqnRoot) AND
        all(j IN range(0, length(p)) WHERE nodes(p)[j].name = $pathSegments[j]) AND
        size(nodes(p)) = size($pathSegments)
      MATCH (file)<-[:CONTAINS]-(:Commit {hash: $commitHash})
      RETURN func;
      """;

  @Inject FileRevisionRepository fileRevisionRepository;

  public Optional<Function> findFunctionByApplicationNameAndFqnAndLandscapeToken(
      final Session session,
      final String applicationName,
      final String[] fqn,
      final String landscapeToken) {
    final String[] functionFilePath = Arrays.copyOfRange(fqn, 0, fqn.length - 1);
    final String functionName = fqn[fqn.length - 1];
    return Optional.ofNullable(
        session.queryForObject(
            Function.class,
            FIND_BY_FQN_AND_LANDSCAPE_TOKEN_STATEMENT,
            Map.of(
                "tokenId",
                landscapeToken,
                "name",
                functionName,
                "pathSegments",
                functionFilePath,
                "appName",
                applicationName)));
  }

  public Optional<Function> findFunctionByApplicationNameAndFqnAndCommitHashAndLandscapeToken(
      final Session session,
      final String applicationName,
      final String[] fqn,
      final String commitHash,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            Function.class,
            FIND_BY_FQN_AND_LANDSCAPE_TOKEN_AND_COMMIT_HASH_STATEMENT,
            Map.of(
                "tokenId",
                landscapeToken,
                "pathSegments",
                fqn,
                "commitHash",
                commitHash,
                "appName",
                applicationName)));
  }

  public Optional<Function> findFunctionByFunctionNameAndFileIdAndClassPath(
      final Session session,
      final String functionName,
      final Long fileId,
      final String[] classPath) {
    return Optional.ofNullable(
        session.queryForObject(
            Function.class,
            """
            MATCH (file:FileRevision)
            WHERE id(file) = $fileId

            MATCH p = (file)-[:CONTAINS]->(:Clazz)
                      -[:CONTAINS]->*(:Clazz)
                      -[:CONTAINS]->(func:Function)

            WITH p, func, nodes(p)[1..] AS cf
            WHERE size(cf) = size($pathSegments)
              AND all(i IN range(0, size($pathSegments)-1)
                  WHERE cf[i].name = $pathSegments[i])

            RETURN func;
            """,
            Map.of(
                "fileId", fileId, "pathSegments", ObjectArrays.concat(classPath, functionName))));
  }

  public Optional<Function> findFunctionByFunctionNameAndClazzId(
      final Session session, final String functionName, final Long clazzId) {
    return Optional.ofNullable(
        session.queryForObject(
            Function.class,
            """
            MATCH (cl:Clazz)
            WHERE id(cl)=$clazzId
            MATCH (cl)-[:CONTAINS]->(f:Function {name: $functionName})
            RETURN f;
            """,
            Map.of("functionName", functionName, "clazzId", clazzId)));
  }

  public Optional<Function> findFunctionByApplicationNameAndFqnAndLandscapeTokenAndClassPath(
      final Session session,
      final String applicationName,
      final String[] fqn,
      final String landscapeToken,
      final String[] classPath) {

    final FileRevision fileRevision =
        fileRevisionRepository
            .findFileRevisionFromAppNameAndPathWithoutCommit(
                session, applicationName, Arrays.copyOf(fqn, fqn.length - 1), landscapeToken)
            .orElse(null);

    if (fileRevision == null) {
      return Optional.empty();
    }

    return findFunctionByFunctionNameAndFileIdAndClassPath(
        session, fqn[fqn.length - 1], fileRevision.getId(), classPath);
  }

  public Optional<Function> findFunctionByAppNameAndFqnAndLandscapeTokenAndCommitHashAndClassPath(
      final Session session,
      final String applicationName,
      final String[] fqn,
      final String landscapeToken,
      final String commitHash,
      final String[] classPath) {
    return fileRevisionRepository
        .findFileRevisionFromAppNameAndCommitHashAndPath(
            session,
            applicationName,
            commitHash,
            Arrays.copyOf(fqn, fqn.length - 1),
            landscapeToken)
        .flatMap(
            fileRevision ->
                findFunctionByFunctionNameAndFileIdAndClassPath(
                    session, fqn[fqn.length - 1], fileRevision.getId(), classPath));
  }

  /**
   * Retrieve all Functions from static analysis along with their fully-qualified names for a given
   * application at a particular commit.
   *
   * @return A map of each function's fqn to the corresponding Function object, separated by '/'.
   *     Note that since the fqn is derived from the node path, it may not be compliant to any
   *     standard notation (e.g. Java).
   */
  public Map<String, Function> findStaticFunctionsWithFqnForApplicationAndCommitAndLandscapeToken(
      final Session session,
      final String applicationName,
      final String commitHash,
      final String landscapeToken) {

    final Map<String, Function> filePathToFunctionMap = new HashMap<>();

    final Result result =
        session.query(
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(a:Application {name: $appName})
              -[:HAS_ROOT]->(appRoot:Directory)
            WHERE (l)
              -[:CONTAINS]->(:Repository)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS*0..]->(appRoot)
            MATCH p = (appRoot)-[:CONTAINS]->*(f:FileRevision)-[:CONTAINS]->(fn:Function)
            WHERE (:Commit {hash: $commitHash})-[:CONTAINS]->(f)
            WITH fn, [node IN nodes(p)[1..] | node.name] AS nodeNames
            RETURN DISTINCT
              fn AS function,
              apoc.text.join(nodeNames, "/") AS fqn;
            """,
            Map.of(
                "tokenId", landscapeToken, "appName", applicationName, "commitHash", commitHash));

    result
        .queryResults()
        .forEach(
            queryResult ->
                filePathToFunctionMap.put(
                    (String) queryResult.get("fqn"), (Function) queryResult.get("function")));

    return filePathToFunctionMap;
  }

  public Optional<Function> findFunctionWithFunctionNameAndFileRevisionId(
      final Session session, final String functionName, final Long fileRevisionId) {
    return Optional.ofNullable(
        session.queryForObject(
            Function.class,
            """
            MATCH (file:FileRevision)
            WHERE id(file)=$fileId
            MATCH (file)-[:CONTAINS]->(f:Function {name: $functionName})
            RETURN f;
            """,
            Map.of("functionName", functionName, "fileId", fileRevisionId)));
  }

  public Optional<Function> findFunction(
      final Session session,
      final String applicationName,
      final String[] fqn,
      final String commitHash,
      final String landscapeToken) {
    return findFunctionByApplicationNameAndFqnAndCommitHashAndLandscapeToken(
        session, applicationName, fqn, commitHash, landscapeToken);
  }

  public Optional<Function> findFunction(
      final Session session,
      final String applicationName,
      final String[] fqn,
      final String landscapeToken,
      final String commitHash,
      final String[] classPath) {
    return findFunctionByAppNameAndFqnAndLandscapeTokenAndCommitHashAndClassPath(
        session, applicationName, fqn, landscapeToken, commitHash, classPath);
  }
}
