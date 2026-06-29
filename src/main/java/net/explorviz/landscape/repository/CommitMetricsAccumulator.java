package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.neo4j.ogm.session.Session;

/**
 * Computes accumulated {@code metrics.*} properties on {@code Commit} nodes once every linked
 * {@code FileRevision} has {@code hasFileData = true}.
 */
@ApplicationScoped
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class CommitMetricsAccumulator {

  @Inject PendingCommitContextRegistry pendingCommitContextRegistry;
  @Inject CommitRepository commitRepository;

  private static final String FIND_PENDING_COMMITS_FOR_HASH =
      """
      MATCH (c:Commit {hash: $commitHash})
      WHERE coalesce(c.hasAccumulatedMetrics, false) = false
        AND EXISTS {
          MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
            -[:CONTAINS]->(c)
        }
        AND EXISTS { MATCH (c)-[:CONTAINS]->(:FileRevision) }
        AND NOT EXISTS {
          MATCH (c)-[:CONTAINS]->(pending:FileRevision)
          WHERE coalesce(pending.hasFileData, false) = false
        }
      RETURN id(c) AS commitId
      """;

  private static final String FIND_PENDING_COMMITS_FOR_REPO =
      """
      MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
        -[:CONTAINS]->(c:Commit)
      WHERE coalesce(c.hasAccumulatedMetrics, false) = false
        AND EXISTS { MATCH (c)-[:CONTAINS]->(:FileRevision) }
        AND NOT EXISTS {
          MATCH (c)-[:CONTAINS]->(pending:FileRevision)
          WHERE coalesce(pending.hasFileData, false) = false
        }
      RETURN id(c) AS commitId
      """;

  private static final String CLEAR_COMMIT_METRICS =
      """
      UNWIND $commitIds AS commitId
      MATCH (c:Commit) WHERE id(c) = commitId
      WITH c, [k IN keys(c) WHERE k STARTS WITH 'metrics.'] AS oldMetricKeys
      FOREACH (k IN oldMetricKeys | SET c[k] = null)
      """;

  private static final String AGGREGATE_COMMIT_METRICS =
      """
      UNWIND $commitIds AS commitId
      MATCH (c:Commit) WHERE id(c) = commitId
      MATCH (c)-[:CONTAINS]->(f:FileRevision)
      WHERE coalesce(f.hasFileData, false) = true
      UNWIND [key IN keys(f) WHERE key STARTS WITH 'metrics.'] AS metricKey
      WITH commitId, substring(metricKey, 8) AS metricName, sum(toFloat(f[metricKey])) AS total
      RETURN commitId, metricName, total
      """;

  private static final String COUNT_LINKED_FILE_REVISIONS =
      """
      UNWIND $commitIds AS commitId
      MATCH (c:Commit) WHERE id(c) = commitId
      OPTIONAL MATCH (c)-[:CONTAINS]->(f:FileRevision)
      RETURN commitId, count(f) AS fileCount
      """;

  private static final String UPDATE_COMMIT_METRICS =
      """
      UNWIND $rows AS row
      MATCH (c:Commit) WHERE id(c) = row.commitId
      SET c += row.props, c.hasAccumulatedMetrics = true
      """;

  /**
   * Recomputes accumulated metrics for every commit in the repository that has file data for all of
   * its file revisions but has not yet been marked as accumulated.
   *
   * <p>Intended to run after file-data transactions commit so concurrent batches are visible, and
   * so any commit missed by an in-transaction check is picked up on a later batch.
   */
  public void updatePendingForRepository(
      final Session session, final String landscapeToken, final String repoName) {
    updatePendingCommits(session, landscapeToken, repoName, null);
  }

  /** Recomputes accumulated metrics for a single commit when it is already fully persisted. */
  public void updatePendingForCommit(
      final Session session,
      final String landscapeToken,
      final String repoName,
      final String commitHash) {
    updatePendingCommits(session, landscapeToken, repoName, commitHash);
  }

  private void updatePendingCommits(
      final Session session,
      final String landscapeToken,
      final String repoName,
      final String commitHash) {
    final List<Long> commitIds = findReadyCommits(session, landscapeToken, repoName, commitHash);
    if (commitIds.isEmpty()) {
      return;
    }

    session.query(CLEAR_COMMIT_METRICS, Map.of("commitIds", commitIds));

    final Map<Long, Map<String, Object>> propsByCommitId = new LinkedHashMap<>();
    session
        .query(AGGREGATE_COMMIT_METRICS, Map.of("commitIds", commitIds))
        .queryResults()
        .forEach(
            row -> {
              final Long commitId = (Long) row.get("commitId");
              final String metricName = (String) row.get("metricName");
              final Double total = ((Number) row.get("total")).doubleValue();
              propsByCommitId
                  .computeIfAbsent(commitId, ignored -> new LinkedHashMap<>())
                  .put("metrics." + metricName, total);
            });

    session
        .query(COUNT_LINKED_FILE_REVISIONS, Map.of("commitIds", commitIds))
        .queryResults()
        .forEach(
            row -> {
              final Long commitId = (Long) row.get("commitId");
              final int fileCount = ((Number) row.get("fileCount")).intValue();
              propsByCommitId
                  .computeIfAbsent(commitId, ignored -> new LinkedHashMap<>())
                  .put("metrics.fileCount", (double) fileCount);
            });

    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Long commitId : commitIds) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("commitId", commitId);
      row.put("props", propsByCommitId.getOrDefault(commitId, Map.of()));
      rows.add(row);
    }

    session.query(UPDATE_COMMIT_METRICS, Map.of("rows", rows));
    Log.debugf(
        "Updated accumulated metrics for %d fully persisted commit(s): %s",
        commitIds.size(), commitIds);
  }

  private List<Long> findReadyCommits(
      final Session session,
      final String landscapeToken,
      final String repoName,
      final String commitHash) {
    final Optional<PendingCommitContextRegistry.PendingCommit> pendingDeferredCommit =
        pendingCommitContextRegistry.find(landscapeToken, repoName);

    final List<Long> commitIds = new ArrayList<>();
    final String query =
        commitHash != null ? FIND_PENDING_COMMITS_FOR_HASH : FIND_PENDING_COMMITS_FOR_REPO;
    final Map<String, Object> params = new LinkedHashMap<>();
    params.put("tokenId", landscapeToken);
    params.put("repoName", repoName);
    params.put("commitHash", commitHash);
    session
        .query(query, params)
        .queryResults()
        .forEach(row -> commitIds.add((Long) row.get("commitId")));

    return commitIds.stream()
        .filter(
            commitId ->
                isReadyForMetricAccumulation(session, commitId, pendingDeferredCommit.orElse(null)))
        .toList();
  }

  /**
   * Commits with deferred file stubs link revisions incrementally as {@code FileData} batches
   * arrive. Defer accumulation until every analyzed file is linked and has data, or until the
   * pending commit is cleared when the next commit is persisted.
   */
  private boolean isReadyForMetricAccumulation(
      final Session session,
      final Long commitId,
      final PendingCommitContextRegistry.PendingCommit pendingDeferredCommit) {
    if (pendingDeferredCommit == null || pendingDeferredCommit.commitInternalId() != commitId) {
      return true;
    }

    return pendingDeferredCommit.analysisFileCount() > 0
        && commitRepository.countLinkedFileRevisions(session, commitId)
            >= pendingDeferredCommit.analysisFileCount();
  }
}
