package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class SocialMetricsRepository {

  @Inject SessionFactory sessionFactory;

  // Base query for commit/file/contributor analysis
  private static final String QUERY_BASE_AGGREGATION =
      """
      MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
            -[:CONTAINS]->(c:Commit)<-[:AUTHORED]-(a:Contributor)
      WHERE c.commitDate >= $from AND c.commitDate <= $to
      MATCH (c)-[:ADDED|MODIFIED]->(f:FileRevision)
      RETURN f.filePath   AS path,
             id(a)        AS aid,
             a.githubLogin AS githubLogin,
             a.gitUsername AS gitUsername,
             count(DISTINCT c) AS commits,
             max(c.commitDate) AS lastDate
      """;

  // files snapshot query for per file loc data
  private static final String QUERY_FILES_SNAPSHOT =
      """
      MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
            -[:CONTAINS]->(anchor:Commit {hash:$commit})-[:CONTAINS]->(f:FileRevision)
      RETURN id(f) AS fileRevisionId,
             f.filePath AS path,
             coalesce(f.`metrics.lineCount`, 0) AS loc
      """;

  public record RepoTimeBounds(long initDate, long lastDate) {}

  public List<ContributorFileActivity> getBaseAggregation(
      final Session session,
      final String token,
      final String repo,
      final Long from,
      final Long to) {
    final List<ContributorFileActivity> rows = new ArrayList<>();
    session
        .query(QUERY_BASE_AGGREGATION, Map.of("token", token, "repo", repo, "from", from, "to", to))
        .queryResults()
        .forEach(
            r ->
                rows.add(
                    new ContributorFileActivity(
                        (String) r.get("path"),
                        ((Number) r.get("aid")).longValue(),
                        (String) r.get("githubLogin"),
                        (String) r.get("gitUsername"),
                        ((Number) r.get("commits")).longValue(),
                        ((Number) r.get("lastDate")).longValue())));
    return rows;
  }

  public List<FileSnapshot> getFileSnapshots(
      final Session session, final String token, final String repo, final String commit) {
    final List<FileSnapshot> rows = new ArrayList<>();
    session
        .query(QUERY_FILES_SNAPSHOT, Map.of("token", token, "repo", repo, "commit", commit))
        .queryResults()
        .forEach(
            r ->
                rows.add(
                    new FileSnapshot(
                        ((Number) r.get("fileRevisionId")).longValue(),
                        (String) r.get("path"),
                        ((Number) r.get("loc")).doubleValue())));
    return rows;
  }

  public RepoTimeBounds getRepoTimeBounds(
      final Session session, final String token, final String repo) {
    final List<RepoTimeBounds> result =
        session.queryDto(
            """
            MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
              -[:CONTAINS]->(c:Commit)
            RETURN min(c.commitDate) AS initDate, max(c.commitDate) AS lastDate
            """,
            Map.of("token", token, "repo", repo),
            RepoTimeBounds.class);
    return result.isEmpty() ? new RepoTimeBounds(0L, 0L) : result.get(0);
  }
}
