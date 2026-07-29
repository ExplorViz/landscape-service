package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import net.explorviz.landscape.ogm.Clazz;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class ClazzRepository {

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
