package net.explorviz.landscape.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.ContributorRepository.ContributorActivity;

public final class SocialMetricsHelper {

  private SocialMetricsHelper() {}

  // to be used with metrics expecting no set = full contributor set
  public static boolean includes(final Set<Long> subset, final long contributorId) {
    return subset.isEmpty() || subset.contains(contributorId);
  }

  public static List<ContributorFileActivity> filterBase(
      final List<ContributorFileActivity> base, final Set<Long> contributorIds) {
    final List<ContributorFileActivity> result = new ArrayList<>();
    for (final ContributorFileActivity fileActivity : base) {
      if (includes(contributorIds, fileActivity.contributorId())) {
        result.add(fileActivity);
      }
    }
    return result;
  }

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
}
