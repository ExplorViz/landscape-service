package net.explorviz.landscape.util;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.explorviz.landscape.repository.ContributorRepository.ContributorActivity;

public final class CoreContributorHelper {

  private CoreContributorHelper() {}

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
