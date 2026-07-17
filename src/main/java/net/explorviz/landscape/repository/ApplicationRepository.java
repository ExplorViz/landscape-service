package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Application;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
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
   * Returns a list of Application objects hydrated with respect to the file structure generated
   * from runtime analysis, meaning all files and corresponding directories gathered from telemetry
   * analysis are fetched. This includes hydrated functions and classes. Empty if no Application is
   * matched.
   */
  public List<Application> fetchAllApplicationsHydratedForRuntimeData(
      final Session session, final String landscapeToken) {
    return Lists.newArrayList(
        session.query(
            Application.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH p = (a:Application)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS]->+(f:FileRevision)
            WHERE
              (l)-[:CONTAINS]->(a:)
              AND f.telemetryKey IS NOT NULL

            MATCH p2 = (f)-[:CONTAINS]->*(endNode)

            UNWIND relationships(p) + relationships(p2) AS rel
            WITH DISTINCT rel AS r
            RETURN startNode(r), r, endNode(r);
            """,
            Map.of("tokenId", landscapeToken)));
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

  /**
   * Fetch the hydrated Application objects containing the files of two particular commits. The
   * commits are hydrated with respect to the file structure of the specified commits, including
   * file contents such as functions and classes. The commits are combined in the sense that any
   * file which is included in either commit is returned, i.e. the union of the commit's file
   * structures is fetched.
   *
   * @param session OGM session object
   * @param firstCommitHash Hash of the first commit whose files to include
   * @param secondCommitHash Hash of the second commit whose files to include
   * @param landscapeToken String identifier of the visualization landscape
   * @return A list of the Application objects belonging to the specified commits, hydrated to
   *     include all files and file contents in the given commits that are part of the application.
   *     Empty if no application is matched.
   */
  public List<Application> fetchApplicationsHydratedForTwoCommits(
      final Session session,
      final String landscapeToken,
      final String firstCommitHash,
      final String secondCommitHash) {
    return Lists.newArrayList(
        session.query(
            Application.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository)
              -[:CONTAINS]->(c:Commit WHERE c.hash = $firstCommitHash OR c.hash = $secondCommitHash)

            MATCH (f:FileRevision)
            WHERE (c)-[:CONTAINS]->(f)

            CALL apoc.path.subgraphAll(f, {
              relationshipFilter: "CONTAINS>"
            })
            YIELD relationships AS fileContentRelations

            MATCH p = (:Application)-[:HAS_ROOT]->(:Directory)-[:CONTAINS]->*(f:FileRevision)

            WITH relationships(p) + fileContentRelations AS rels
            UNWIND rels as r
            RETURN DISTINCT startNode(r), r, endNode(r);
            """,
            Map.of(
                "tokenId",
                landscapeToken,
                "firstCommitHash",
                firstCommitHash,
                "secondCommitHash",
                secondCommitHash)));
  }

  /**
   * Return the names of all application nodes in a landscape which are contained in a repository,
   * i.e. the names of all applications which have static data available.
   */
  public List<String> findStaticApplicationNamesForLandscapeToken(
      final Session session, final String landscapeToken) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a:Application)
            WHERE (l)
              -[:CONTAINS]->(:Repository)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS*0..]->(:Directory)<-[:HAS_ROOT]-(a)
            RETURN DISTINCT a.name
            ORDER BY a.name ASC;
            """,
            Map.of("tokenId", landscapeToken)));
  }
}
