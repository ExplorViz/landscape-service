package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.FileRevision;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseObjectForClearerAPI"})
public class ClazzRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Clazz> findClassDynamicData(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String[] filePathSegments,
      final String classNameSegments) {
    return Optional.ofNullable(
        session.queryForObject(
            Clazz.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(app:Application {name: $appName})
              -[:HAS_ROOT]->(appRoot:Directory)
            MATCH p = (pathRoot:Directory|FileRevision)-[:CONTAINS]->*(file:FileRevision)
            WHERE
              (appRoot)-[:CONTAINS]->(pathRoot) AND
              NOT (:Commit)-[:CONTAINS]->(file)) AND
              all(j IN range(0, length(p)) WHERE nodes(p)[j].name = $filePathSegments[j]) AND
              size(nodes(p)) = size($filePathSegments)
            MATCH p = (:Clazz)-[:CONTAINS]->*(class:Clazz)
            WHERE
              (file)-[:CONTAINS]->(class) AND
              all(j IN range(0, length(p)) WHERE nodes(p)[j].name = $classNameSegments[j]) AND
              size(nodes(p)) = size($classNameSegments)
            OPTIONAL MATCH (class)-[r]->(other)
            RETURN DISTINCT class, r, other;
            """,
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "filePathSegments",
                filePathSegments,
                "classNameSegments",
                classNameSegments)));
  }

  public Optional<Clazz> findClassByClassPathAndFileRevisionId(
      final Session session, final String[] classPath, final Long fileRevisionId) {
    return Optional.ofNullable(
        session.queryForObject(
            Clazz.class,
            """
            MATCH (file:FileRevision)
            WHERE id(file) = $fileId

            MATCH p = (file)-[:CONTAINS]->*(:Clazz)
            WHERE
            all(j IN range(0, length(p)) WHERE nodes(p)[j+1].name = $pathSegments[j]) AND
            size(nodes(p))-1 = size($pathSegments)

            RETURN last(nodes(p));
            """,
            Map.of("pathSegments", classPath, "fileId", fileRevisionId)));
  }

  public Optional<Map<String, Object>> findLongestMatchingClassPathByFileRevisionsId(
      final Session session, final String[] classPath, final Long fileRevisionId) {
    final Result result =
        session.query(
            """
            MATCH (file:FileRevision)
            WHERE id(file) = $fileId

            OPTIONAL MATCH p = (file)-[:CONTAINS]->*(:Clazz)

            WITH p, nodes(p)[1..] AS classes
            WHERE classes IS NULL
              OR  all(i IN range(0, size(classes)-1)
                WHERE classes[i].name = $pathSegments[i])

            ORDER BY size(classes) DESC
            LIMIT 1

            RETURN
            CASE
              WHEN classes IS NULL or size(classes) = 0 THEN null
              ELSE classes[-1]
            END AS existingClass,
            $pathSegments[coalesce(size(classes),0)..] AS remainingPath
            """,
            Map.of("pathSegments", classPath, "fileId", fileRevisionId));

    final Iterator<Map<String, Object>> resultIterator = result.queryResults().iterator();
    if (!resultIterator.hasNext()) {
      return Optional.empty();
    }

    return Optional.of(resultIterator.next());
  }

  /*
  The fileId is a fallback. If findLongestMatchingClassPathByFileRevisionsId doesn't find any
  existing clazz, then the whole clazz path will be created and the first one will be added to
  the corresponding FileRevision
  */
  @SuppressWarnings("PMD.NullAssignment")
  public Clazz createClazzPathAndReturnLastClazz(
      final Session session, final String[] classPath, final Long fileRevisionId) {
    final Map<String, Object> resultMap =
        findLongestMatchingClassPathByFileRevisionsId(session, classPath, fileRevisionId)
            .orElse(null);

    Clazz existingClazz = null;

    if (resultMap != null) {
      existingClazz = resultMap.get("existingClass") instanceof Clazz cl ? cl : null;
    }
    final String[] remainingPath;

    if (existingClazz == null) {
      final FileRevision fileRevision =
          session.queryForObject(
              FileRevision.class,
              """
              MATCH (f:FileRevision)
              WHERE id(f)=$fileId
              RETURN f;
              """,
              Map.of("fileId", fileRevisionId));
      existingClazz = new Clazz(classPath[0]);
      fileRevision.addClass(existingClazz);
      session.save(fileRevision);
      remainingPath = Arrays.copyOfRange(classPath, 1, classPath.length);
    } else {
      remainingPath = resultMap.get("remainingPath") instanceof String[] rp ? rp : new String[0];
    }

    Clazz lastClazz = existingClazz;
    for (final String clazzName : remainingPath) {
      final Clazz newClazz = new Clazz(clazzName);
      lastClazz.addInnerClass(newClazz);
      lastClazz = newClazz;
    }
    session.save(existingClazz);

    return lastClazz;
  }

  public Optional<Clazz> findClassByLandscapeTokenAndRepositoryAndFileHashAndClazzName(
      final Session session,
      final String tokenId,
      final String repoName,
      final String fileHash,
      final String[] pathSegments,
      final String clazzName) {
    return Optional.ofNullable(
        session.queryForObject(
            Clazz.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
              -[:HAS_ROOT]->(root:Directory)
            MATCH p = (root)-[:CONTAINS]->*(file:FileRevision {hash: $fileHash})
            WHERE all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $pathSegments[j-1])
              AND length(p) = size($pathSegments)
            MATCH (file)-[:CONTAINS]->(cl:Clazz {name: $clazzName})
            RETURN cl
            LIMIT 1;
            """,
            Map.of(
                "tokenId",
                tokenId,
                "repoName",
                repoName,
                "fileHash",
                fileHash,
                "pathSegments",
                pathSegments,
                "clazzName",
                clazzName)));
  }

  public Optional<Clazz> findClassByLandscapeTokenAndRepositoryAndClazzFqn(
      final Session session,
      final String tokenId,
      final String repoName,
      final String[] splitSuperFqn) {
    if (splitSuperFqn.length != 2) { // NOPMD - literal is intentional here
      throw new IllegalArgumentException(
          "Format of super class invalid:\n"
              + "   Expected format: path/to/file/file.extension::class \n"
              + "   Actual value: "
              + String.join("::", splitSuperFqn));
    }
    final String superClassName = splitSuperFqn[1];
    final String[] pathSegments = splitSuperFqn[0].split("/");

    return Optional.ofNullable(
        session.queryForObject(
            Clazz.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
              -[:HAS_ROOT]->(root:Directory)
            MATCH p = (root)-[:CONTAINS]->*(file:FileRevision)
            WHERE all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $pathSegments[j-1])
              AND length(p) = size($pathSegments)
            MATCH (file)-[:CONTAINS]->(cl:Clazz {name: $clazzName})
            WITH cl, file
            ORDER BY file.hasFileData DESC, id(file) DESC
            LIMIT 1
            RETURN cl;
            """,
            Map.of(
                "tokenId",
                tokenId,
                "repoName",
                repoName,
                "clazzName",
                superClassName,
                "pathSegments",
                pathSegments)));
  }

  public Optional<Clazz> findClassFromInheritingClass(
      final Session session, final String tokenId, final String repoName, final String clazzName) {
    return Optional.ofNullable(
        session.queryForObject(
            Clazz.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(:Commit)
              -[:CONTAINS]->(:FileRevision)
              -[:CONTAINS]->(:Clazz)
              -[:INHERITS]->(cl:Clazz {name: $clazzName})
            RETURN cl
            LIMIT 1;
            """,
            Map.of("tokenId", tokenId, "repoName", repoName, "clazzName", clazzName)));
  }

  /**
   * Retrieve all classes from static analysis along with their fully-qualified names for a given
   * application at a particular commit.
   *
   * @return A map of each class's fqn to the corresponding Clazz object, separated by '/'. To
   *     account for inner classes, the filename is followed by the class name. Note that since the
   *     fqn is derived from the node path, it may not be compliant to any standard notation (e.g.
   *     Java).
   */
  public Map<String, Clazz> findStaticClassesWithFqnForApplicationAndCommitAndLandscapeToken(
      final Session session,
      final String applicationName,
      final String commitHash,
      final String landscapeToken) {

    final Map<String, Clazz> filePathToClazzMap = new HashMap<>();

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
            MATCH p = (appRoot)-[:CONTAINS]->*(f:FileRevision)-[:CONTAINS]->(c:Clazz)
            WHERE (:Commit {hash: $commitHash})-[:CONTAINS]->(f)
            WITH c, [node IN nodes(p)[1..] | node.name] AS nodeNames
            RETURN DISTINCT
              c AS clazz,
              apoc.text.join(nodeNames, "/") AS fqn;
            """,
            Map.of(
                "tokenId", landscapeToken, "appName", applicationName, "commitHash", commitHash));

    result
        .queryResults()
        .forEach(
            queryResult ->
                filePathToClazzMap.put(
                    (String) queryResult.get("fqn"), (Clazz) queryResult.get("clazz")));

    return filePathToClazzMap;
  }
}
