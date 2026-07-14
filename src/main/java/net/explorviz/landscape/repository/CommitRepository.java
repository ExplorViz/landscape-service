package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import io.quarkus.logging.Log;
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
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
public class CommitRepository {

  @Inject SessionFactory sessionFactory;

  @Inject ParentCommitLookupProperties parentCommitLookupProperties;

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
    if (applicationNames == null || applicationNames.isEmpty()) {
      final Optional<String> cachedHash =
          findLatestFullyPersistedCommitHashOnBranch(session, repoName, tokenId, branchName);
      final Optional<Commit> cachedCommit =
          cachedHash.isPresent()
              ? findCommitByHashAndLandscapeToken(session, cachedHash.get(), tokenId)
              : Optional.empty();
      if (cachedCommit.isPresent()
          && isFullyPersistedCommit(session, tokenId, cachedCommit.get(), List.of())) {
        return cachedCommit;
      }
    }

    return Optional.ofNullable(
        session.queryForObject(
            Commit.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(repo:Repository {name: $repoName})
            CALL (l, repo) {
              MATCH (repo)-[:CONTAINS]->(c:Commit)-[:BELONGS_TO]->(:Branch {name: $branchName})
              WHERE coalesce(c.hasAccumulatedMetrics, false) = true
                AND ($applicationNames IS NULL OR size($applicationNames) = 0
                  OR all(appName IN $applicationNames WHERE EXISTS {
                    MATCH (l)-[:CONTAINS]->(:Application {name: appName})-[:HAS_ROOT]->(root:Directory)
                    MATCH (c)-[:CONTAINS]->(f:FileRevision)
                    MATCH (root)-[:CONTAINS*0..]->(:Directory)-[:CONTAINS*0..]->(f)
                  }))
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
                applicationNames != null ? applicationNames : List.of())));
  }

  public Optional<String> findLatestFullyPersistedCommitHashOnBranch(
      final Session session, final String repoName, final String tokenId, final String branchName) {
    return Optional.ofNullable(
        session.queryForObject(
            String.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(b:Branch {name: $branchName})
            WHERE b.latestFullyPersistedCommitHash IS NOT NULL
            RETURN b.latestFullyPersistedCommitHash
            LIMIT 1
            """,
            Map.of("tokenId", tokenId, "repoName", repoName, "branchName", branchName)));
  }

  public void updateLatestFullyPersistedCommitOnBranch(
      final Session session,
      final long commitInternalId,
      final String repoName,
      final String tokenId) {
    session.query(
        """
        MATCH (c:Commit) WHERE id(c) = $commitId
        MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c)-[:BELONGS_TO]->(b:Branch)
        WHERE coalesce(c.hasAccumulatedMetrics, false) = true
          AND c.commitDate >= coalesce(b.latestFullyPersistedCommitDate, 0)
        SET b.latestFullyPersistedCommitHash = c.hash,
            b.latestFullyPersistedCommitDate = c.commitDate
        """,
        Map.of(
            "commitId", commitInternalId,
            "tokenId", tokenId,
            "repoName", repoName));
  }

  private boolean isFullyPersistedCommit(
      final Session session,
      final String tokenId,
      final Commit commit,
      final List<String> applicationNames) {
    if (!commit.isHasAccumulatedMetrics()) {
      return false;
    }
    final Boolean stillValid =
        session.queryForObject(
            Boolean.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (l)-[:CONTAINS]->(:Repository)-[:CONTAINS]->(c:Commit {hash: $commitHash})
            RETURN coalesce(c.hasAccumulatedMetrics, false) = true
              AND ($applicationNames IS NULL OR size($applicationNames) = 0
                OR all(appName IN $applicationNames WHERE EXISTS {
                  MATCH (l)-[:CONTAINS]->(:Application {name: appName})-[:HAS_ROOT]->(root:Directory)
                  MATCH (c)-[:CONTAINS]->(f:FileRevision)
                  MATCH (root)-[:CONTAINS*0..]->(:Directory)-[:CONTAINS*0..]->(f)
                }))
            LIMIT 1
            """,
            Map.of(
                "tokenId", tokenId,
                "commitHash", commit.getHash(),
                "applicationNames", applicationNames));
    return Boolean.TRUE.equals(stillValid);
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
            MATCH (repo)-[:CONTAINS]->(c:Commit)-[r:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[h:HAS_PARENT]->(parent:Commit)
            OPTIONAL MATCH (parent)-[pr:BELONGS_TO]->(pb:Branch)
            OPTIONAL MATCH (c)-[tagRel:IS_TAGGED_WITH]->(tag:Tag)
            RETURN DISTINCT c, r, b, h, parent, pr, pb, tagRel, tag
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
            MATCH (repo)-[:CONTAINS]->(c:Commit)-[r:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[h:HAS_PARENT]->(parent:Commit)
            OPTIONAL MATCH (parent)-[pr:BELONGS_TO]->(pb:Branch)
            OPTIONAL MATCH (c)-[tagRel:IS_TAGGED_WITH]->(tag:Tag)
            RETURN DISTINCT c, r, b, h, parent, pr, pb, tagRel, tag
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

  /**
   * Finds a commit hash scoped to a repository, including commits only reachable from the
   * repository via {@code HAS_PARENT} (for example git parents created as stubs when linking a
   * child commit).
   */
  public Optional<Long> findCommitInternalIdInRepository(
      final Session session, final String commitHash, final String tokenId, final String repoName) {
    return Optional.ofNullable(
        session.queryForObject(
            Long.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
            MATCH (c:Commit {hash: $commitHash})
            WHERE EXISTS { MATCH (repo)-[:CONTAINS]->(c) }
              OR EXISTS { MATCH (repo)-[:CONTAINS]->(:Commit)-[:HAS_PARENT*1..10]->(c) }
            RETURN id(c) LIMIT 1
            """,
            Map.of("tokenId", tokenId, "repoName", repoName, "commitHash", commitHash)));
  }

  /**
   * Like {@link #findCommitInternalIdInRepository} but only matches commits that were fully
   * analyzed (branch metadata and at least one linked file revision).
   */
  public Optional<Long> findAnalyzedCommitInternalIdInRepository(
      final Session session, final String commitHash, final String tokenId, final String repoName) {
    return Optional.ofNullable(
        session.queryForObject(
            Long.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
            MATCH (c:Commit {hash: $commitHash})-[:BELONGS_TO]->(:Branch)
            WHERE EXISTS { MATCH (repo)-[:CONTAINS]->(c) }
              AND EXISTS { MATCH (c)-[:CONTAINS]->(:FileRevision) }
            RETURN id(c) LIMIT 1
            """,
            Map.of("tokenId", tokenId, "repoName", repoName, "commitHash", commitHash)));
  }

  /**
   * Resolves a parent commit internal id, retrying briefly so a just-committed parent becomes
   * visible before unchanged-file inheritance is skipped.
   */
  public Optional<Long> findCommitInternalIdInRepositoryWithRetry(
      final Session session, final String commitHash, final String tokenId, final String repoName) {
    return findCommitInternalIdInRepositoryWithRetry(
        session,
        commitHash,
        tokenId,
        repoName,
        Math.max(1, parentCommitLookupProperties.maxAttempts()),
        Math.max(0L, parentCommitLookupProperties.retryDelayMs()),
        false);
  }

  public Optional<Long> findCommitInternalIdInRepositoryWithRetry(
      final Session session,
      final String commitHash,
      final String tokenId,
      final String repoName,
      final boolean requirePersistedParent) {
    if (!requirePersistedParent) {
      return findCommitInternalIdInRepositoryWithRetry(session, commitHash, tokenId, repoName);
    }
    return findCommitInternalIdInRepositoryWithRetry(
        session,
        commitHash,
        tokenId,
        repoName,
        Math.max(1, parentCommitLookupProperties.requiredMaxAttempts()),
        Math.max(0L, parentCommitLookupProperties.requiredRetryDelayMs()),
        true);
  }

  private Optional<Long> findCommitInternalIdInRepositoryWithRetry(
      final Session session,
      final String commitHash,
      final String tokenId,
      final String repoName,
      final int maxAttempts,
      final long retryDelayMs,
      final boolean requireAnalyzedParent) {

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      final Optional<Long> commitInternalId =
          requireAnalyzedParent
              ? findAnalyzedCommitInternalIdInRepository(session, commitHash, tokenId, repoName)
              : findCommitInternalIdInRepository(session, commitHash, tokenId, repoName);

      if (commitInternalId.isPresent()) {
        return commitInternalId;
      }

      if (attempt < maxAttempts && !waitForRetry(session, retryDelayMs, commitHash, repoName)) {
        return Optional.empty();
      }
    }

    return Optional.empty();
  }

  private boolean waitForRetry(
      final Session session,
      final long retryDelayMs,
      final String commitHash,
      final String repoName) {

    if (retryDelayMs <= 0) {
      return true;
    }

    try {
      Thread.sleep(retryDelayMs);
      session.clear();
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.debugf(
          "Interrupted while waiting for parent commit %s in repository '%s'",
          commitHash, repoName);
      return false;
    }
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

  /**
   * Like {@link #findLatestCommitWithLinkedFilesInternalId} but without requiring a branch link.
   * Used when branch metadata is missing on older commits.
   */
  public Optional<Long> findLatestCommitWithLinkedFilesInRepositoryInternalId(
      final Session session,
      final String landscapeTokenId,
      final String repoName,
      final long excludeCommitInternalId) {
    return Optional.ofNullable(
        session.queryForObject(
            Long.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit)
            WHERE EXISTS { MATCH (c)-[:CONTAINS]->(:FileRevision) }
              AND ($excludeCommitId <= 0 OR id(c) <> $excludeCommitId)
            RETURN id(c) AS commitId
            ORDER BY c.commitDate DESC
            LIMIT 1
            """,
            Map.of(
                "tokenId", landscapeTokenId,
                "repoName", repoName,
                "excludeCommitId", excludeCommitInternalId)));
  }

  public Commit getOrCreateCommit(
      final Session session, final String commitHash, final String tokenId) {
    return findCommitByHashAndLandscapeToken(session, commitHash, tokenId)
        .orElse(new Commit(commitHash));
  }
}
