package net.explorviz.landscape.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import net.explorviz.landscape.api.v3.model.CommitSampling;
import net.explorviz.landscape.ogm.Commit;

/** Applies timestamp range and sampling filters to branch-ordered commits. */
public final class CommitTreeFilterer {

  private CommitTreeFilterer() {}

  /**
   * Filters {@code orderedCommits} by optional epoch-millisecond bounds, then optionally keeps only
   * the first commit in each sampling bucket (chronological order is preserved).
   */
  public static List<Commit> applyFilters(
      final List<Commit> orderedCommits,
      final Long fromEpochMs,
      final Long toEpochMs,
      final CommitSampling sampling) {

    final List<Commit> timestampFiltered =
        filterByTimestamp(orderedCommits, fromEpochMs, toEpochMs);

    if (sampling == CommitSampling.NONE) {
      return timestampFiltered;
    }

    return sampleCommits(timestampFiltered, sampling);
  }

  private static List<Commit> filterByTimestamp(
      final List<Commit> orderedCommits, final Long fromEpochMs, final Long toEpochMs) {

    final long from = fromEpochMs != null ? fromEpochMs : Long.MIN_VALUE;
    final long to = toEpochMs != null ? toEpochMs : Long.MAX_VALUE;

    return orderedCommits.stream()
        .filter(
            commit -> {
              final Instant commitDate = commit.getCommitDate();
              if (commitDate == null) {
                return true;
              }
              final long epochMs = commitDate.toEpochMilli();
              return epochMs >= from && epochMs <= to;
            })
        .toList();
  }

  private static List<Commit> sampleCommits(
      final List<Commit> orderedCommits, final CommitSampling sampling) {

    if (orderedCommits.isEmpty()) {
      return orderedCommits;
    }

    final List<Commit> sampled = new ArrayList<>();
    String currentBucket = null;

    for (final Commit commit : orderedCommits) {
      final Instant commitDate = commit.getCommitDate();
      if (commitDate == null) {
        sampled.add(commit);
        continue;
      }

      final String bucket = bucketKey(commitDate, sampling);
      if (!bucket.equals(currentBucket)) {
        sampled.add(commit);
        currentBucket = bucket;
      }
    }

    return sampled;
  }

  private static String bucketKey(final Instant commitDate, final CommitSampling sampling) {
    final ZonedDateTime zonedDateTime = commitDate.atZone(ZoneOffset.UTC);

    return switch (sampling) {
      case DAILY -> zonedDateTime.toLocalDate().toString();
      case MONTHLY -> zonedDateTime.getYear() + "-" + zonedDateTime.getMonthValue();
      case YEARLY -> String.valueOf(zonedDateTime.getYear());
      default -> throw new IllegalArgumentException("Unsupported sampling: " + sampling);
    };
  }
}
