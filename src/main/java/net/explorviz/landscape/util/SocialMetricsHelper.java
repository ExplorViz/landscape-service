package net.explorviz.landscape.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.ContributorRepository.ContributorActivity;

/** Contains util methods for social metrics calculation. */
public final class SocialMetricsHelper {

  private SocialMetricsHelper() {}

  /**
   * Safely checks whether a subset of the contributorIds set contains a given contributorId.
   *
   * @param subset the set of contributorIds to check against
   * @param contributorId the id of the contributor to check for
   * @return whether the subset includes the contributor
   */
  public static boolean includes(final Set<Long> subset, final long contributorId) {
    return subset.isEmpty() || subset.contains(contributorId);
  }

  /**
   * Extracts the core contributor set from the list of ContributorActivity records. The core
   * contributor set is defined as the set of contributors making up at least 80% of commits in the
   * repository.
   *
   * @param rows the list of ContributorActivity rows to count
   * @return the set of core contributors
   */
  public static Set<Long> computeCoreContributorIds(final List<ContributorActivity> rows) {
    long totalCommits = 0;
    for (final ContributorActivity row : rows) {
      totalCommits += row.commitCount();
    }

    final Set<Long> result = new HashSet<>();
    long agg = 0;
    for (final ContributorActivity row : rows) {
      agg += row.commitCount();
      result.add(row.contributorId());
      if (5 * agg >= 4 * totalCommits) {
        break;
      }
    }
    return result;
  }

  /**
   * Extracts per file path commit counts from the base aggregation, filtered by a set of
   * contributorIds.
   *
   * @param base the base aggregation query result list
   * @param contributorIds the set of contributorIds to filter by
   * @return a map of file path to commit count.
   */
  public static Map<String, Long> getCommitCountByPath(
      final List<ContributorFileActivity> base, final Set<Long> contributorIds) {
    final Map<String, Long> commitCountByPath = new HashMap<>();
    for (final ContributorFileActivity row : base) {
      if (includes(contributorIds, row.contributorId())) {
        commitCountByPath.merge(row.path(), row.commits(), Long::sum);
      }
    }
    return commitCountByPath;
  }
}
