package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.neo4j.ogm.session.Session;

/**
 * Computes accumulated {@code metrics.*} properties on {@code Commit} nodes once every linked
 * {@code FileRevision} has {@code hasFileData = true}.
 *
 * <p>Summing every linked file revision is prohibitive on large repositories: a commit in a
 * Linux-kernel-sized history links roughly 100,000 file revisions, and reading each one's metric
 * properties costs millions of database hits for a result that is a handful of numbers. Almost all
 * of those revisions are the very same nodes the parent commit already summed, so metrics are
 * normally derived from the parent's totals plus the difference between the two commits' {@code
 * CONTAINS} sets — see {@link #accumulateFromParent}. Commits without an accumulated parent fall
 * back to a full sum over their own files.
 *
 * <p>For each metric, the number of files that have it is recorded as {@code metricFileCounts.*}.
 * Clients divide by it for averages. Like the totals, it is derived from the parent where possible.
 *
 * <p>A metric that none of the files has is omitted, so it can be told apart from a total of zero.
 */
@ApplicationScoped
public class CommitMetricsAccumulator {

  private static final String METRIC_PREFIX = "metrics.";
  private static final String FILE_COUNT_METRIC = METRIC_PREFIX + "fileCount";
  private static final String FILE_COUNT_PREFIX = "metricFileCounts.";

  @Inject PendingCommitContextRegistry pendingCommitContextRegistry;
  @Inject CommitRepository commitRepository;
  @Inject CommitMetricNameRegistry metricNameRegistry;
  @Inject PendingMetricsCommitTracker pendingMetricsCommitTracker;

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
      WITH c, [k IN keys(c)
        WHERE k STARTS WITH 'metrics.' OR k STARTS WITH 'metricFileCounts.'] AS oldMetricKeys
      FOREACH (k IN oldMetricKeys | SET c[k] = null)
      """;

  private static final String FIND_ACCUMULATED_PARENT =
      """
      MATCH (c:Commit) WHERE id(c) = $commitId
      MATCH (c)-[:HAS_FIRST_PARENT]->(p:Commit)
      WHERE coalesce(p.hasAccumulatedMetrics, false) = true
      WITH p, [k IN keys(p)
        WHERE k STARTS WITH 'metrics.' OR k STARTS WITH 'metricFileCounts.'] AS metricKeys
      RETURN
        id(p) AS parentCommitId,
        metricKeys,
        [k IN metricKeys | p[k]] AS metricValues
      LIMIT 1
      """;

  /**
   * Reads both commits' linked file revision ids and diffs them inside the database, so only the
   * (normally tiny) difference crosses the wire instead of two 100,000-element id lists.
   */
  private static final String DIFF_CONTAINS_SETS =
      """
      MATCH (parent:Commit) WHERE id(parent) = $parentCommitId
      MATCH (parent)-[:CONTAINS]->(pf:FileRevision)
      WITH collect(id(pf)) AS parentFileRevIds
      MATCH (child:Commit) WHERE id(child) = $childCommitId
      MATCH (child)-[:CONTAINS]->(cf:FileRevision)
      WITH parentFileRevIds, collect(id(cf)) AS childFileRevIds
      RETURN
        apoc.coll.subtract(childFileRevIds, parentFileRevIds) AS addedFileRevIds,
        apoc.coll.subtract(parentFileRevIds, childFileRevIds) AS removedFileRevIds,
        size(childFileRevIds) AS childFileCount
      """;

  private static final String SUM_METRICS_OF_FILE_REVISIONS =
      """
      UNWIND $fileRevIds AS fileRevId
      MATCH (f:FileRevision)
      WHERE id(f) = fileRevId AND coalesce(f.hasFileData, false) = true
      UNWIND [key IN keys(f) WHERE key STARTS WITH 'metrics.'] AS metricKey
      RETURN substring(metricKey, 8) AS metricName, sum(toFloat(f[metricKey])) AS total,
        count(f[metricKey]) AS fileCount
      """;

  private static final String SUM_METRICS_BY_KEY_DISCOVERY =
      """
      MATCH (c:Commit) WHERE id(c) = $commitId
      MATCH (c)-[:CONTAINS]->(f:FileRevision)
      WHERE coalesce(f.hasFileData, false) = true
      UNWIND [key IN keys(f) WHERE key STARTS WITH 'metrics.'] AS metricKey
      RETURN substring(metricKey, 8) AS metricName, sum(toFloat(f[metricKey])) AS total,
        count(f[metricKey]) AS fileCount
      """;

  private static final String UPDATE_COMMIT_METRICS =
      """
      UNWIND $rows AS row
      MATCH (c:Commit) WHERE id(c) = row.commitId
      SET c += row.props, c.hasAccumulatedMetrics = true
      """;

  private record ParentBaseline(long commitId, MetricTotals totals) {}

  /** Sums of metrics and the numbers of files that have them. */
  private record MetricTotals(Map<String, Double> sums, Map<String, Double> fileCounts) {

    private MetricTotals() {
      this(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    private void add(final String metricName, final double sum, final double fileCount) {
      sums.merge(metricName, sum, Double::sum);
      fileCounts.merge(metricName, fileCount, Double::sum);
    }
  }

  private record ContainsSetDiff(
      List<Long> addedFileRevIds, List<Long> removedFileRevIds, int childFileCount) {

    private int changedCount() {
      return addedFileRevIds.size() + removedFileRevIds.size();
    }
  }

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

  /**
   * Recomputes accumulated metrics for the commit the repository is currently ingesting, once all
   * of its file data arrived. Looking up that one commit keeps the check independent of the number
   * of commits in the repository, see {@link PendingMetricsCommitTracker}.
   *
   * <p>Falls back to {@link #updatePendingForRepository} if no commit is tracked for the
   * repository, which also picks up commits a previous analysis left behind, and while a commit
   * with deferred file stubs is pending, as that one only completes after its successor arrived.
   */
  public void updatePendingForTrackedCommit(
      final Session session, final String landscapeToken, final String repoName) {
    final Optional<PendingMetricsCommitTracker.TrackedCommit> trackedCommit =
        pendingMetricsCommitTracker.find(landscapeToken, repoName);
    if (trackedCommit.isEmpty()
        || pendingCommitContextRegistry.find(landscapeToken, repoName).isPresent()) {
      updatePendingForRepository(session, landscapeToken, repoName);
    } else if (!trackedCommit.get().accumulated()) {
      updatePendingForCommit(session, landscapeToken, repoName, trackedCommit.get().commitHash());
    }
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

    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final Long commitId : commitIds) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("commitId", commitId);
      row.put("props", accumulateMetrics(session, landscapeToken, repoName, commitId));
      rows.add(row);
    }

    session.query(UPDATE_COMMIT_METRICS, Map.of("rows", rows));

    for (final Long commitId : commitIds) {
      commitRepository.updateLatestFullyPersistedCommitOnBranch(
          session, commitId, repoName, landscapeToken);
    }

    if (commitHash != null) {
      pendingMetricsCommitTracker.markAccumulated(landscapeToken, repoName, commitHash);
    }

    Log.debugf(
        "Updated accumulated metrics for %d fully persisted commit(s): %s",
        commitIds.size(), commitIds);
  }

  private Map<String, Object> accumulateMetrics(
      final Session session,
      final String landscapeToken,
      final String repoName,
      final long commitId) {
    return accumulateFromParent(session, commitId)
        .orElseGet(() -> accumulateFromAllFiles(session, landscapeToken, repoName, commitId));
  }

  /**
   * Derives the commit's totals from an already-accumulated first parent: the parent's sums, minus
   * the file revisions the parent had and this commit does not, plus the ones this commit added.
   * The first parent is used because file inheritance and diffs are based on it.
   *
   * <p>Empty when there is no accumulated parent, when it lacks file counts because it was
   * accumulated before they existed, or when the two commits share so few files that summing this
   * commit's own files outright is the cheaper option.
   */
  private Optional<Map<String, Object>> accumulateFromParent(
      final Session session, final long commitId) {
    final Optional<ParentBaseline> baseline = findAccumulatedParent(session, commitId);
    if (baseline.isEmpty()) {
      return Optional.empty();
    }

    final Optional<ContainsSetDiff> diff =
        diffContainsSets(session, baseline.get().commitId(), commitId);
    if (diff.isEmpty() || diff.get().changedCount() >= diff.get().childFileCount()) {
      return Optional.empty();
    }

    final ContainsSetDiff delta = diff.get();
    final MetricTotals totals = baseline.get().totals();
    addMetricTotals(session, delta.addedFileRevIds(), totals, 1);
    addMetricTotals(session, delta.removedFileRevIds(), totals, -1);

    Log.debugf(
        "Accumulated metrics for commit %d from parent %d: %d file revision(s) added, "
            + "%d removed, %d linked in total",
        commitId,
        baseline.get().commitId(),
        delta.addedFileRevIds().size(),
        delta.removedFileRevIds().size(),
        delta.childFileCount());

    return Optional.of(toProps(totals, delta.childFileCount()));
  }

  private Map<String, Object> accumulateFromAllFiles(
      final Session session,
      final String landscapeToken,
      final String repoName,
      final long commitId) {
    final Set<String> metricNames = metricNameRegistry.namesFor(session, landscapeToken, repoName);
    final MetricTotals totals =
        metricNames.isEmpty()
            ? sumMetricsByKeyDiscovery(session, commitId)
            : sumMetricsByName(session, commitId, List.copyOf(metricNames));
    return toProps(totals, commitRepository.countLinkedFileRevisions(session, commitId));
  }

  private Optional<ParentBaseline> findAccumulatedParent(
      final Session session, final long commitId) {
    final Iterator<Map<String, Object>> rowIterator =
        session
            .query(FIND_ACCUMULATED_PARENT, Map.of("commitId", commitId))
            .queryResults()
            .iterator();
    if (!rowIterator.hasNext()) {
      return Optional.empty();
    }

    final Map<String, Object> row = rowIterator.next();
    final MetricTotals totals = new MetricTotals();
    final List<String> metricKeys = toStringList(row.get("metricKeys"));
    final List<Double> metricValues = toDoubleList(row.get("metricValues"));
    for (int i = 0; i < metricKeys.size() && i < metricValues.size(); i++) {
      final String key = metricKeys.get(i);
      if (key.startsWith(FILE_COUNT_PREFIX)) {
        totals.fileCounts().put(key.substring(FILE_COUNT_PREFIX.length()), metricValues.get(i));
      } else if (!FILE_COUNT_METRIC.equals(key)) {
        totals.sums().put(key.substring(METRIC_PREFIX.length()), metricValues.get(i));
      }
    }
    if (!totals.fileCounts().keySet().equals(totals.sums().keySet())) {
      return Optional.empty();
    }
    return Optional.of(new ParentBaseline((Long) row.get("parentCommitId"), totals));
  }

  private Optional<ContainsSetDiff> diffContainsSets(
      final Session session, final long parentCommitId, final long childCommitId) {
    final Iterator<Map<String, Object>> rowIterator =
        session
            .query(
                DIFF_CONTAINS_SETS,
                Map.of("parentCommitId", parentCommitId, "childCommitId", childCommitId))
            .queryResults()
            .iterator();
    if (!rowIterator.hasNext()) {
      return Optional.empty();
    }

    final Map<String, Object> row = rowIterator.next();
    return Optional.of(
        new ContainsSetDiff(
            toLongList(row.get("addedFileRevIds")),
            toLongList(row.get("removedFileRevIds")),
            ((Number) row.get("childFileCount")).intValue()));
  }

  private void addMetricTotals(
      final Session session,
      final List<Long> fileRevIds,
      final MetricTotals totals,
      final double sign) {
    for (int offset = 0;
        offset < fileRevIds.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, fileRevIds.size());
      session
          .query(
              SUM_METRICS_OF_FILE_REVISIONS, Map.of("fileRevIds", fileRevIds.subList(offset, end)))
          .queryResults()
          .forEach(
              row ->
                  totals.add(
                      (String) row.get("metricName"),
                      sign * ((Number) row.get("total")).doubleValue(),
                      sign * ((Number) row.get("fileCount")).doubleValue()));
    }
  }

  /**
   * Sums the given metrics by reading each property directly. Naming the properties avoids {@code
   * keys(f)}, which reads every property on every file node just to find the metric ones and is the
   * bulk of the cost when a commit links tens of thousands of files.
   */
  private MetricTotals sumMetricsByName(
      final Session session, final long commitId, final List<String> metricNames) {
    final StringBuilder query =
        new StringBuilder(256 + metricNames.size() * 112)
            .append(
                """
                MATCH (c:Commit) WHERE id(c) = $commitId
                MATCH (c)-[:CONTAINS]->(f:FileRevision)
                WHERE coalesce(f.hasFileData, false) = true
                RETURN\
                """);
    for (int i = 0; i < metricNames.size(); i++) {
      final String property = (METRIC_PREFIX + metricNames.get(i)).replace("`", "``");
      query
          .append(i == 0 ? " " : ", ")
          .append("sum(toFloat(f.`")
          .append(property)
          .append("`)) AS metric")
          .append(i)
          .append(", count(f.`")
          .append(property)
          .append("`) AS fileCount")
          .append(i);
    }

    final MetricTotals totals = new MetricTotals();
    session
        .query(query.toString(), Map.of("commitId", commitId))
        .queryResults()
        .forEach(
            row -> {
              for (int i = 0; i < metricNames.size(); i++) {
                final Object total = row.get("metric" + i);
                if (total instanceof Number number
                    && row.get("fileCount" + i) instanceof Number fileCount) {
                  totals.add(metricNames.get(i), number.doubleValue(), fileCount.doubleValue());
                }
              }
            });
    return totals;
  }

  private MetricTotals sumMetricsByKeyDiscovery(final Session session, final long commitId) {
    final MetricTotals totals = new MetricTotals();
    session
        .query(SUM_METRICS_BY_KEY_DISCOVERY, Map.of("commitId", commitId))
        .queryResults()
        .forEach(
            row ->
                totals.add(
                    (String) row.get("metricName"),
                    ((Number) row.get("total")).doubleValue(),
                    ((Number) row.get("fileCount")).doubleValue()));
    return totals;
  }

  private Map<String, Object> toProps(final MetricTotals totals, final int fileCount) {
    final Map<String, Object> props = new LinkedHashMap<>();
    totals
        .fileCounts()
        .forEach(
            (metricName, count) -> {
              if (count > 0) {
                props.put(METRIC_PREFIX + metricName, totals.sums().get(metricName));
                props.put(FILE_COUNT_PREFIX + metricName, count);
              }
            });
    props.put(FILE_COUNT_METRIC, (double) fileCount);
    return props;
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

  private static List<Long> toLongList(final Object value) {
    final List<Long> values = new ArrayList<>();
    if (value instanceof long[] array) {
      for (final long element : array) {
        values.add(element);
      }
    } else if (value instanceof Object[] array) {
      for (final Object element : array) {
        values.add(((Number) element).longValue());
      }
    } else if (value instanceof Collection<?> collection) {
      collection.forEach(element -> values.add(((Number) element).longValue()));
    }
    return values;
  }

  private static List<String> toStringList(final Object value) {
    final List<String> values = new ArrayList<>();
    if (value instanceof Object[] array) {
      for (final Object element : array) {
        values.add(String.valueOf(element));
      }
    } else if (value instanceof Collection<?> collection) {
      collection.forEach(element -> values.add(String.valueOf(element)));
    }
    return values;
  }

  private static List<Double> toDoubleList(final Object value) {
    final List<Double> values = new ArrayList<>();
    if (value instanceof double[] array) {
      for (final double element : array) {
        values.add(element);
      }
    } else if (value instanceof Object[] array) {
      for (final Object element : array) {
        values.add(((Number) element).doubleValue());
      }
    } else if (value instanceof Collection<?> collection) {
      collection.forEach(element -> values.add(((Number) element).doubleValue()));
    }
    return values;
  }
}
