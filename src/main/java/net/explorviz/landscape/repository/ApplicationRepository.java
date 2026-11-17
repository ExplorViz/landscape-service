package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Application;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class ApplicationRepository {

  /**
   * Returns the application object with the given name within the landscape with the provided
   * token. The application is hydrated to contain its root directory, and additionally any files
   * and subdirectories that are directly contained within the root directory.
   */
  public Optional<Application> findApplicationByNameAndLandscapeToken(
      final Session session, final String name, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(app:Application {name: $name})
              -[h:HAS_ROOT]->(appRoot:Directory)
            OPTIONAL MATCH (appRoot)-[c:CONTAINS]->(n:FileRevision|Directory)
            RETURN app, h, appRoot, c, n;
            """,
            Map.of("tokenId", tokenId, "name", name)));
  }

  /**
   * Returns a list of all Application objects that are contained in the given repository. The
   * application objects are hydrated with respect to the file structure of a specific commit,
   * meaning all files and corresponding directories within the commit are also fetched. This
   * includes fully hydrated functions and classes. Empty if no Application is matched.
   */
  public List<Application> fetchHydratedApplicationsInRepositoryForCommit(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final String commitHash) {
    return Lists.newArrayList(
        session.query(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit {hash: $commitHash})
            MATCH (c)-[:CONTAINS]->(f:FileRevision)

            CALL apoc.path.subgraphAll(f, {
              relationshipFilter: "CONTAINS>"
            })
            YIELD relationships AS fileContentRels

            MATCH p = (:Application)-[:HAS_ROOT]->(:Directory)-[:CONTAINS]->*(f:FileRevision)

            WITH relationships(p) + fileContentRels AS rels
            UNWIND rels as r
            RETURN DISTINCT startNode(r), r, endNode(r);
            """,
            Map.of(
                "tokenId", landscapeToken, "repoName", repositoryName, "commitHash", commitHash)));
  }
}
