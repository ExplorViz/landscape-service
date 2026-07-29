package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import net.explorviz.landscape.ogm.Function;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class FunctionRepository {

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
}
