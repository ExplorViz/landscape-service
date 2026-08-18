package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import net.explorviz.landscape.api.v3.model.CommitSampling;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import org.junit.jupiter.api.Test;

class CommitTreeFiltererTest {

  private static final Branch MAIN = new Branch("main");

  @Test
  void applyFilters_returnsAllCommitsWhenNoFiltersAreSet() {
    final List<Commit> commits =
        List.of(
            commit("c1", Instant.parse("2024-01-01T10:00:00Z")),
            commit("c2", Instant.parse("2024-01-02T10:00:00Z")));

    final List<Commit> filtered =
        CommitTreeFilterer.applyFilters(commits, null, null, CommitSampling.NONE);

    assertEquals(List.of("c1", "c2"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void applyFilters_filtersByTimestampRange() {
    final List<Commit> commits =
        List.of(
            commit("c1", Instant.parse("2024-01-01T10:00:00Z")),
            commit("c2", Instant.parse("2024-01-15T10:00:00Z")),
            commit("c3", Instant.parse("2024-02-01T10:00:00Z")));

    final long from = Instant.parse("2024-01-10T00:00:00Z").toEpochMilli();
    final long to = Instant.parse("2024-01-31T23:59:59Z").toEpochMilli();

    final List<Commit> filtered =
        CommitTreeFilterer.applyFilters(commits, from, to, CommitSampling.NONE);

    assertEquals(List.of("c2"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void applyFilters_dailySamplingKeepsFirstCommitPerDay() {
    final List<Commit> commits =
        List.of(
            commit("c1", Instant.parse("2024-01-01T08:00:00Z")),
            commit("c2", Instant.parse("2024-01-01T18:00:00Z")),
            commit("c3", Instant.parse("2024-01-02T08:00:00Z")),
            commit("c4", Instant.parse("2024-01-03T08:00:00Z")));

    final List<Commit> filtered =
        CommitTreeFilterer.applyFilters(commits, null, null, CommitSampling.DAILY);

    assertEquals(List.of("c1", "c3", "c4"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void applyFilters_monthlySamplingKeepsFirstCommitPerMonth() {
    final List<Commit> commits =
        List.of(
            commit("c1", Instant.parse("2024-01-05T08:00:00Z")),
            commit("c2", Instant.parse("2024-01-20T08:00:00Z")),
            commit("c3", Instant.parse("2024-02-01T08:00:00Z")),
            commit("c4", Instant.parse("2024-03-10T08:00:00Z")));

    final List<Commit> filtered =
        CommitTreeFilterer.applyFilters(commits, null, null, CommitSampling.MONTHLY);

    assertEquals(List.of("c1", "c3", "c4"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void applyFilters_yearlySamplingKeepsFirstCommitPerYear() {
    final List<Commit> commits =
        List.of(
            commit("c1", Instant.parse("2023-06-01T08:00:00Z")),
            commit("c2", Instant.parse("2023-12-01T08:00:00Z")),
            commit("c3", Instant.parse("2024-01-01T08:00:00Z")),
            commit("c4", Instant.parse("2025-03-01T08:00:00Z")));

    final List<Commit> filtered =
        CommitTreeFilterer.applyFilters(commits, null, null, CommitSampling.YEARLY);

    assertEquals(List.of("c1", "c3", "c4"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void applyFilters_appliesTimestampFilterBeforeSampling() {
    final List<Commit> commits =
        List.of(
            commit("c1", Instant.parse("2024-01-01T08:00:00Z")),
            commit("c2", Instant.parse("2024-01-01T18:00:00Z")),
            commit("c3", Instant.parse("2024-02-01T08:00:00Z")),
            commit("c4", Instant.parse("2024-03-01T08:00:00Z")));

    final long from = Instant.parse("2024-01-15T00:00:00Z").toEpochMilli();
    final long to = Instant.parse("2024-02-28T23:59:59Z").toEpochMilli();

    final List<Commit> filtered =
        CommitTreeFilterer.applyFilters(commits, from, to, CommitSampling.MONTHLY);

    assertEquals(List.of("c3"), filtered.stream().map(Commit::getHash).toList());
  }

  private static Commit commit(final String hash, final Instant commitDate) {
    final Commit commit = new Commit(hash);
    commit.setBranch(MAIN);
    commit.setCommitDate(commitDate);
    return commit;
  }
}
