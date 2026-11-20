package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.repository.ContributorRepository.ContributorActivity;
import net.explorviz.landscape.repository.FileLineages.CommitLink;
import net.explorviz.landscape.repository.FileLineages.RenameEvent;
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

  // every single change behind the base aggregation, for paths whose files were renamed
  private static final String QUERY_COMMIT_FILE_ACTIVITY =
      """
      MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
            -[:CONTAINS]->(c:Commit)<-[:AUTHORED]-(a:Contributor)
      WHERE c.commitDate >= $from AND c.commitDate <= $to
      MATCH (c)-[:ADDED|MODIFIED]->(f:FileRevision)
      WHERE f.filePath IN $paths
      RETURN DISTINCT f.filePath AS path,
             id(a)        AS aid,
             id(c)        AS commitId,
             c.commitDate AS commitDate
      """;

  private static final String QUERY_COMMIT_LINKS =
      """
      MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
            -[:CONTAINS]->(c:Commit)
      OPTIONAL MATCH (c)-[:HAS_FIRST_PARENT|HAS_MISC_PARENT]->(parent:Commit)
      RETURN id(c) AS commitId,
             c.hash AS hash,
             coalesce(c.commitDate, 0) AS commitDate,
             id(parent) AS parentId
      """;

  // The renaming commit is the one that added the new revision; the edge is keyed by its hash
  // because the same pair of revisions can be related by renames of several commits.
  private static final String QUERY_RENAMES =
      """
      MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
            -[:CONTAINS]->(c:Commit)-[:ADDED]->(renamed:FileRevision)
            -[rename:RENAMED_FROM]->(previous:FileRevision)
      WHERE rename.commitHash = c.hash
      RETURN DISTINCT id(c) AS commitId,
             previous.filePath AS oldPath,
             renamed.filePath  AS newPath
      """;

  private static final String QUERY_PULL_REQUEST_FILE_CHANGES =
      """
      MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
            -[:CONTAINS]->(pr:PullRequest)-[:CONTAINS]->(c:Commit)
            -[:ADDED|MODIFIED]->(f:FileRevision)
      WHERE f.filePath IN $paths
      OPTIONAL MATCH (pr)-[:REFERENCES]->(i:Issue)
      RETURN DISTINCT id(pr) AS prId,
             f.filePath AS path,
             id(c)      AS commitId,
             id(i)      AS issueId
      """;

  public record RepoTimeBounds(long initDate, long lastDate) {}

  /** A single change of a file by a commit, the unit the base aggregation counts. */
  public record CommitFileActivity(
      String path, long contributorId, long commitId, long commitDate) {}

  /**
   * A single change of a file by a commit of a pull request, once per issue the pull request
   * references. {@code issueId} is {@code null} if it references none.
   */
  public record PullRequestFileChange(long prId, String path, long commitId, Long issueId) {}

  public record MergedPrStats(long prId, long lifetime, long commentCount, List<String> paths) {}

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
                        ((Number) r.get("commits")).longValue(),
                        ((Number) r.get("lastDate")).longValue())));
    return rows;
  }

  /**
   * Returns the changes that {@link #getBaseAggregation} counts for the given paths without
   * aggregating them, so that they can be attributed to files instead of paths.
   */
  public List<CommitFileActivity> getCommitFileActivity(
      final Session session,
      final String token,
      final String repo,
      final Long from,
      final Long to,
      final Collection<String> paths) {
    final List<CommitFileActivity> rows = new ArrayList<>();
    session
        .query(
            QUERY_COMMIT_FILE_ACTIVITY,
            Map.of("token", token, "repo", repo, "from", from, "to", to, "paths", paths))
        .queryResults()
        .forEach(
            r ->
                rows.add(
                    new CommitFileActivity(
                        (String) r.get("path"),
                        ((Number) r.get("aid")).longValue(),
                        ((Number) r.get("commitId")).longValue(),
                        ((Number) r.get("commitDate")).longValue())));
    return rows;
  }

  /** Returns every commit of the repository, once per parent. */
  public List<CommitLink> getCommitLinks(
      final Session session, final String token, final String repo) {
    final List<CommitLink> rows = new ArrayList<>();
    session
        .query(QUERY_COMMIT_LINKS, Map.of("token", token, "repo", repo))
        .queryResults()
        .forEach(
            r ->
                rows.add(
                    new CommitLink(
                        ((Number) r.get("commitId")).longValue(),
                        (String) r.get("hash"),
                        ((Number) r.get("commitDate")).longValue(),
                        r.get("parentId") instanceof Number parentId
                            ? parentId.longValue()
                            : null)));
    return rows;
  }

  /** Returns all files that were renamed by a commit of the repository. */
  public List<RenameEvent> getRenames(
      final Session session, final String token, final String repo) {
    final List<RenameEvent> rows = new ArrayList<>();
    session
        .query(QUERY_RENAMES, Map.of("token", token, "repo", repo))
        .queryResults()
        .forEach(
            r ->
                rows.add(
                    new RenameEvent(
                        ((Number) r.get("commitId")).longValue(),
                        (String) r.get("oldPath"),
                        (String) r.get("newPath"))));
    return rows;
  }

  /** Returns the changes that pull requests made to the given paths. */
  public List<PullRequestFileChange> getPullRequestFileChanges(
      final Session session,
      final String token,
      final String repo,
      final Collection<String> paths) {
    final List<PullRequestFileChange> rows = new ArrayList<>();
    session
        .query(
            QUERY_PULL_REQUEST_FILE_CHANGES, Map.of("token", token, "repo", repo, "paths", paths))
        .queryResults()
        .forEach(
            r ->
                rows.add(
                    new PullRequestFileChange(
                        ((Number) r.get("prId")).longValue(),
                        (String) r.get("path"),
                        ((Number) r.get("commitId")).longValue(),
                        r.get("issueId") instanceof Number issueId ? issueId.longValue() : null)));
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

  public List<MergedPrStats> getMergedPrStats(
      final Session session, final String token, final String repo) {
    final List<MergedPrStats> rows = new ArrayList<>();

    session
        .query(
            """
            MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
                  -[:CONTAINS]->(pr:PullRequest)-[:HAS_VERSION]->(mv:ResourceVersion {state:'MERGED'})
            MATCH (pr)-[:HAS_VERSION]->(v:ResourceVersion)
            WITH pr, min(v.creationDate) AS createdAt, min(mv.creationDate) AS mergedAt
            OPTIONAL MATCH (pr)-[:HAS_VERSION]->(:ResourceVersion)<-[:GENERATES]-(a:ResourceAnnotation)
              WHERE a.annotationType IN ['COMMENT','REVIEW']
            WITH pr, createdAt, mergedAt, count(DISTINCT a) AS commentCount
            MATCH (pr)-[:CONTAINS]->(:Commit)-[:ADDED|MODIFIED]->(f:FileRevision)
            RETURN id(pr) AS prId, mergedAt - createdAt AS lifetimeMillis,
                   commentCount, collect(DISTINCT f.filePath) AS paths
            """,
            Map.of("token", token, "repo", repo))
        .queryResults()
        .forEach(
            r -> {
              final List<String> paths = new ArrayList<>();
              final Object rawPaths = r.get("paths");
              if (rawPaths instanceof Object[] arr) {
                for (final Object path : arr) {
                  paths.add((String) path);
                }
              }
              rows.add(
                  new MergedPrStats(
                      ((Number) r.get("prId")).longValue(),
                      ((Number) r.get("lifetimeMillis")).longValue(),
                      ((Number) r.get("commentCount")).longValue(),
                      paths));
            });
    return rows;
  }

  public Map<String, Long> getIssueCountByPath(
      final Session session, final String token, final String repo) {
    final Map<String, Long> issueCountByPath = new HashMap<>();
    session
        .query(
            """
            MATCH (:Landscape {tokenId:$token})-[:CONTAINS]->(:Repository {name:$repo})
                  -[:CONTAINS]->(pr:PullRequest)-[:REFERENCES]->(i:Issue)
            MATCH (pr)-[:CONTAINS]->(:Commit)-[:ADDED|MODIFIED]->(f:FileRevision)
            RETURN f.filePath AS path, count(DISTINCT i) AS issueCount
            """,
            Map.of("token", token, "repo", repo))
        .queryResults()
        .forEach(
            r ->
                issueCountByPath.put(
                    (String) r.get("path"), ((Number) r.get("issueCount")).longValue()));

    return issueCountByPath;
  }

  public List<ContributorActivity> getContributorData(
      final Session session, final String token, final String repo) {
    return session.queryDto(
        """
        MATCH (:Landscape {tokenId: $token})-[:CONTAINS]->(:Repository {name: $repo})
                    -[:CONTAINS]->(c:Commit)<-[:AUTHORED]-(a:Contributor)
        RETURN id(a)             AS contributorId,
               a.gitUsername     AS gitUsername,
               a.githubLogin     AS githubLogin,
               a.email           AS email,
               a.avatarUrl       AS avatarUrl,
               count(DISTINCT c) AS commitCount,
               min(c.commitDate) AS minDate,
               max(c.commitDate) AS maxDate
        ORDER BY commitCount DESC, contributorId ASC
        """,
        Map.of("token", token, "repo", repo),
        ContributorActivity.class);
  }
}
