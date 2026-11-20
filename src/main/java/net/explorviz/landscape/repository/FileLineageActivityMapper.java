package net.explorviz.landscape.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.repository.SocialMetricsRepository.CommitFileActivity;
import net.explorviz.landscape.repository.SocialMetricsRepository.MergedPrStats;
import net.explorviz.landscape.repository.SocialMetricsRepository.PullRequestFileChange;

/**
 * Re-keys per-path aggregations so that the changes a file received under a previous path are
 * attributed to the path it has in the snapshot commit.
 *
 * <p>Aggregations of paths that were never part of a rename are passed through untouched. For the
 * paths that were, the aggregated values cannot be reused, because one path may have belonged to
 * different files over time; they are recomputed from the individual changes.
 */
public final class FileLineageActivityMapper {

  private static final class Activity {
    private final Set<Long> commitIds = new HashSet<>();
    private long lastDate = Long.MIN_VALUE;
  }

  private FileLineageActivityMapper() {}

  /**
   * Replaces the commit activity of all renamed paths.
   *
   * @param base activity aggregated per path and contributor
   * @param trackedPathChanges every single change of a {@link FileLineages#trackedPaths() tracked
   *     path}, restricted to the same commits as {@code base}
   */
  public static List<ContributorFileActivity> mapCommitActivity(
      final List<ContributorFileActivity> base,
      final List<CommitFileActivity> trackedPathChanges,
      final FileLineages lineages) {
    final Set<String> trackedPaths = lineages.trackedPaths();
    final List<ContributorFileActivity> mapped = new ArrayList<>(base.size());
    for (final ContributorFileActivity row : base) {
      if (!trackedPaths.contains(row.path())) {
        mapped.add(row);
      }
    }

    final Map<String, Map<Long, Activity>> activityByPathAndContributor = new LinkedHashMap<>();
    for (final CommitFileActivity change : trackedPathChanges) {
      for (final String currentPath : lineages.currentPathsOf(change.path(), change.commitId())) {
        final Activity activity =
            activityByPathAndContributor
                .computeIfAbsent(currentPath, path -> new LinkedHashMap<>())
                .computeIfAbsent(change.contributorId(), id -> new Activity());
        activity.commitIds.add(change.commitId());
        activity.lastDate = Math.max(activity.lastDate, change.commitDate());
      }
    }
    activityByPathAndContributor.forEach(
        (path, activityByContributor) ->
            activityByContributor.forEach(
                (contributorId, activity) ->
                    mapped.add(
                        new ContributorFileActivity(
                            path, contributorId, activity.commitIds.size(), activity.lastDate))));
    return mapped;
  }

  /** Replaces the renamed paths in the files that were changed by each pull request. */
  public static List<MergedPrStats> mapMergedPrStats(
      final List<MergedPrStats> mergedPrStats,
      final List<PullRequestFileChange> trackedPathChanges,
      final FileLineages lineages) {
    final Map<Long, Set<String>> currentPathsByPrId = new HashMap<>();
    for (final PullRequestFileChange change : trackedPathChanges) {
      currentPathsByPrId
          .computeIfAbsent(change.prId(), id -> new LinkedHashSet<>())
          .addAll(lineages.currentPathsOf(change.path(), change.commitId()));
    }

    final Set<String> trackedPaths = lineages.trackedPaths();
    final List<MergedPrStats> mapped = new ArrayList<>(mergedPrStats.size());
    for (final MergedPrStats pr : mergedPrStats) {
      final Set<String> paths = new LinkedHashSet<>();
      for (final String path : pr.paths()) {
        if (!trackedPaths.contains(path)) {
          paths.add(path);
        }
      }
      paths.addAll(currentPathsByPrId.getOrDefault(pr.prId(), Set.of()));
      mapped.add(
          new MergedPrStats(pr.prId(), pr.lifetime(), pr.commentCount(), List.copyOf(paths)));
    }
    return mapped;
  }

  /** Replaces the issue counts of all renamed paths. */
  public static Map<String, Long> mapIssueCounts(
      final Map<String, Long> issueCountByPath,
      final List<PullRequestFileChange> trackedPathChanges,
      final FileLineages lineages) {
    final Map<String, Long> mapped = new HashMap<>(issueCountByPath);
    mapped.keySet().removeAll(lineages.trackedPaths());

    final Map<String, Set<Long>> issueIdsByPath = new HashMap<>();
    for (final PullRequestFileChange change : trackedPathChanges) {
      if (change.issueId() == null) {
        continue;
      }
      for (final String currentPath : lineages.currentPathsOf(change.path(), change.commitId())) {
        issueIdsByPath.computeIfAbsent(currentPath, path -> new HashSet<>()).add(change.issueId());
      }
    }
    issueIdsByPath.forEach((path, issueIds) -> mapped.put(path, (long) issueIds.size()));
    return mapped;
  }
}
