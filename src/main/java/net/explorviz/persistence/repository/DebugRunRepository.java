package net.explorviz.persistence.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.DebugRun;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class DebugRunRepository {

  public List<DebugRun> findDebugRunsForRepositoryAndLandscapeToken(
      final Session session, final String repositoryName, final String landscapeToken) {
    return Lists.newArrayList(
        session.query(
            DebugRun.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_DEBUG_RUN]->(d:DebugRun)
                  -[r2:RUNS_ON]->(c:Commit)
            OPTIONAL MATCH (d)-[r1:CONTAINS]->(ds:DebugSnapshot)
            OPTIONAL MATCH (d)-[r2:RUNS_ON]->(c:Commit)
            RETURN d, r1, ds, r2, c;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repositoryName)));
  }

  public Optional<Commit> findCommitForDebugRunAndRepositoryAndLandscapeToken(
      final Session session,
      final String debugRunId,
      final String repositoryName,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName)
                  -[:HAS_DEBUG_RUN]->(:DebugRun {id: $debugId})
                  -[:RUNS_ON]->(c:Commit)
            RETURN c;
            """,
            Map.of("debugId", debugRunId, "repoName", repositoryName, "tokenId", landscapeToken)));
  }
}
