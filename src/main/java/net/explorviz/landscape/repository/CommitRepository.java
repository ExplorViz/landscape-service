package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.explorviz.landscape.ogm.Commit;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class CommitRepository {

  @Inject SessionFactory sessionFactory;

  /**
   * Find latest commit for which we have seen a CommitData message and also a FileData message for
   * every file included in the commit.
   */
  public Optional<Commit> findLatestFullyPersistedCommit(
      final Session session,
      final String repoName,
      final String tokenId,
      final String branchName,
      final List<String> applicationNames) {
    return Optional.ofNullable(
        session.queryForObject(
            Commit.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(repo:Repository {name: $repoName})
            CALL (l, repo) {
              MATCH (repo)-[:CONTAINS]->(c:Commit)-[:BELONGS_TO]->(:Branch {name: $branchName})
              WHERE EXISTS { MATCH (c)-[:CONTAINS]->(:FileRevision) }
                AND NOT EXISTS {
                  MATCH (c)-[:CONTAINS]->(f:FileRevision)
                  WHERE coalesce(f.hasFileData, false) = false
                }
                AND all(appName IN $applicationNames WHERE EXISTS {
                  MATCH (l)-[:CONTAINS]->(:Application {name: appName})-[:HAS_ROOT]->(root:Directory)
                  MATCH (root)-[:CONTAINS*1..]->(:FileRevision)<-[:CONTAINS]-(c)
                })
              RETURN c
              ORDER BY c.commitDate DESC
              LIMIT 1
            }
            RETURN c;
            """,
            Map.of(
                "tokenId",
                tokenId,
                "repoName",
                repoName,
                "branchName",
                branchName,
                "applicationNames",
                applicationNames)));
  }

  public Optional<Commit> findCommitByHashAndLandscapeToken(
      final Session session, final String commitHash, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository)
              -[:CONTAINS]->(c:Commit {hash: $commitHash})
            RETURN c;
            """,
            Map.of("tokenId", tokenId, "commitHash", commitHash)));
  }

  /**
   * Returns every commit (along with its branch and parent relations) in the specified repository,
   * ordered by author date (ascending, meaning oldest first).
   */
  public List<Commit> findCommitsWithBranchForRepositoryAndLandscapeToken(
      final Session session, final String landscapeToken, final String repositoryName) {
    return Lists.newArrayList(
        session.query(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
            MATCH (repo)-[:CONTAINS]->(c:Commit)
            OPTIONAL MATCH (c)-[r:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[h:HAS_PARENT]->(parent:Commit)
            OPTIONAL MATCH (parent)-[pr:BELONGS_TO]->(pb:Branch)
            RETURN DISTINCT c, r, b, h, parent, pr, pb
            ORDER BY c.authorDate ASC;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repositoryName)));
  }

  /**
   * Returns every commit (along with its branch and parent relations) in the specified repository
   * for a given application, ordered by author date (ascending, meaning oldest first).
   */
  public List<Commit> findCommitsWithBranchForApplicationAndLandscapeToken(
      final Session session, final String landscapeToken, final String applicationName) {
    return Lists.newArrayList(
        session.query(
            Commit.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a:Application {name: $appName})
            MATCH (repo:Repository)<-[:CONTAINS]-(l)
            WHERE (repo)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*0..]->(:Directory)<-[:HAS_ROOT]-(a)
            MATCH (repo)-[:CONTAINS]->(c:Commit)
            OPTIONAL MATCH (c)-[r:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[h:HAS_PARENT]->(parent:Commit)
            OPTIONAL MATCH (parent)-[pr:BELONGS_TO]->(pb:Branch)
            RETURN DISTINCT c, r, b, h, parent, pr, pb
            ORDER BY c.authorDate ASC;
            """,
            Map.of("tokenId", landscapeToken, "appName", applicationName)));
  }

  public Optional<Long> findCommitInternalId(
      final Session session, final String commitHash, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Long.class,
            """
            MATCH (c:Commit {hash: $commitHash})
            WHERE EXISTS {
              MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository)-[:CONTAINS]->(c)
            }
            RETURN id(c) LIMIT 1
            """,
            Map.of("tokenId", tokenId, "commitHash", commitHash)));
  }

  public int countLinkedFileRevisions(final Session session, final long commitInternalId) {
    final Integer count =
        session.queryForObject(
            Integer.class,
            """
            MATCH (c:Commit) WHERE id(c) = $commitId
            OPTIONAL MATCH (c)-[:CONTAINS]->(f:FileRevision)
            RETURN count(f) AS fileCount
            """,
            Map.of("commitId", commitInternalId));
    return count != null ? count : 0;
  }

  public Optional<Long> findParentCommitInternalId(
      final Session session, final long commitInternalId) {
    return Optional.ofNullable(
        session.queryForObject(
            Long.class,
            """
            MATCH (c:Commit) WHERE id(c) = $commitId
            MATCH (c)-[:HAS_PARENT]->(parent:Commit)
            RETURN id(parent) AS parentId
            LIMIT 1
            """,
            Map.of("commitId", commitInternalId)));
  }

  public int countExplicitlyChangedFileLinks(final Session session, final long commitInternalId) {
    final Integer count =
        session.queryForObject(
            Integer.class,
            """
            MATCH (c:Commit) WHERE id(c) = $commitId
            OPTIONAL MATCH (c)-[:ADDED|MODIFIED]->(f:FileRevision)
            RETURN count(DISTINCT f) AS changedCount
            """,
            Map.of("commitId", commitInternalId));
    return count != null ? count : 0;
  }

  @SuppressWarnings("unchecked")
  public Set<String> findExplicitlyChangedFilePaths(
      final Session session, final long commitInternalId) {
    final List<String> paths =
        session.queryForObject(
            List.class,
            """
            MATCH (c:Commit) WHERE id(c) = $commitId
            MATCH (c)-[:ADDED|MODIFIED]->(f:FileRevision)
            RETURN collect(DISTINCT coalesce(f.filePath, f.name)) AS paths
            """,
            Map.of("commitId", commitInternalId));
    return paths != null ? new HashSet<>(paths) : Set.of();
  }

  /**
   * Returns the most recent commit on a branch that already has file revisions linked, excluding
   * {@code excludeCommitInternalId} when set to a positive internal id.
   */
  public Optional<Long> findLatestCommitWithLinkedFilesInternalId(
      final Session session,
      final String landscapeTokenId,
      final String repoName,
      final String branchName,
      final long excludeCommitInternalId) {
    final Map<String, Object> params = new HashMap<>();
    params.put("tokenId", landscapeTokenId);
    params.put("repoName", repoName);
    params.put("branchName", branchName);
    params.put("excludeCommitId", excludeCommitInternalId);

    return Optional.ofNullable(
        session.queryForObject(
            Long.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit)-[:BELONGS_TO]->(:Branch {name: $branchName})
            WHERE EXISTS { MATCH (c)-[:CONTAINS]->(:FileRevision) }
              AND ($excludeCommitId <= 0 OR id(c) <> $excludeCommitId)
            RETURN id(c) AS commitId
            ORDER BY c.commitDate DESC
            LIMIT 1
            """,
            params));
  }

  public Commit getOrCreateCommit(
      final Session session, final String commitHash, final String tokenId) {
    return findCommitByHashAndLandscapeToken(session, commitHash, tokenId)
        .orElse(new Commit(commitHash));
  }
}
