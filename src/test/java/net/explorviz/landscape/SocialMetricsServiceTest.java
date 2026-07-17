package net.explorviz.landscape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.repository.SocialMetricsService;
import org.junit.jupiter.api.Test;

public class SocialMetricsServiceTest {

  private static final long ALICE = 1L;
  private static final long BOB = 2L;

  /** A: alice 2 + bob 1 = 3, B: alice 1. */
  private static List<ContributorFileActivity> baseFixture() {
    return List.of(
        new ContributorFileActivity("A", ALICE, "alice", "alice", 2L, 0L),
        new ContributorFileActivity("A", BOB, "bob", "bob", 1L, 0L),
        new ContributorFileActivity("B", ALICE, "alice", "alice", 1L, 0L));
  }

  /** 101 -> A, 102 -> B, 103 -> C. */
  private static List<FileSnapshot> snapshotFixture() {
    return List.of(
        new FileSnapshot(101, "A", 99),
        new FileSnapshot(102, "B", 9),
        new FileSnapshot(103, "C", 999));
  }

  @Test
  void basicMultiFile() {
    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(
            baseFixture(), snapshotFixture(), Set.of(ALICE, BOB));

    // V = {3, 1, 0} -> p95 = 3
    assertEquals(3, files.size());

    assertEquals(3d, files.get(101L).raw());
    assertEquals(1.0, files.get(101L).normalized());

    assertEquals(1d, files.get(102L).raw());
    assertEquals(1 / 3d, files.get(102L).normalized());

    assertEquals(0d, files.get(103L).raw());
    assertEquals(0.0, files.get(103L).normalized());
  }

  @Test
  void contributorSubset() {
    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(baseFixture(), snapshotFixture(), Set.of(BOB));

    // V = {1, 0, 0} -> p95 = 1
    assertEquals(1d, files.get(101L).raw());
    assertEquals(1.0, files.get(101L).normalized());

    assertEquals(0d, files.get(102L).raw());
    assertEquals(0.0, files.get(102L).normalized());

    assertEquals(0d, files.get(103L).raw());
    assertEquals(0.0, files.get(103L).normalized());
  }

  @Test
  void emptyContributorsMeansAll() {
    Map<Long, MetricScore> all =
        SocialMetricsService.computeCommitActivity(
            baseFixture(), snapshotFixture(), Set.of(ALICE, BOB));
    Map<Long, MetricScore> empty =
        SocialMetricsService.computeCommitActivity(baseFixture(), snapshotFixture(), Set.of());

    assertEquals(all, empty);
  }

  @Test
  void fileInBaseButNotSnapshotExcluded() {
    List<ContributorFileActivity> base =
        List.of(
            new ContributorFileActivity("A", ALICE, "alice", "alice", 2L, 0L),
            new ContributorFileActivity("A", BOB, "bob", "bob", 1L, 0L),
            new ContributorFileActivity("B", ALICE, "alice", "alice", 1L, 0L),
            new ContributorFileActivity("D", ALICE, "alice", "alice", 5L, 0L));
    List<FileSnapshot> snapshot =
        List.of(new FileSnapshot(101, "A", 99), new FileSnapshot(102, "B", 9));

    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(base, snapshot, Set.of(ALICE, BOB));

    assertEquals(List.of(101L, 102L), List.copyOf(files.keySet()));

    // V = {3, 1} -> p95 = 3. If D's 5 leaked in, V = {3, 1, 5} -> p95 = 5 and A would be 0.6.
    assertEquals(3d, files.get(101L).raw());
    assertEquals(1.0, files.get(101L).normalized());

    assertEquals(1d, files.get(102L).raw());
    assertEquals(1 / 3d, files.get(102L).normalized());
  }

  @Test
  void untouchedSnapshotFileScoresZeroAndIsInMultiset() {
    List<ContributorFileActivity> base =
        List.of(new ContributorFileActivity("A", ALICE, "alice", "alice", 10L, 0L));

    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(base, snapshotFixture(), Set.of(ALICE, BOB));

    assertEquals(3, files.size());
    assertTrue(files.containsKey(102L));
    assertTrue(files.containsKey(103L));

    assertEquals(10d, files.get(101L).raw());
    assertEquals(1.0, files.get(101L).normalized());

    assertEquals(0d, files.get(102L).raw());
    assertEquals(0.0, files.get(102L).normalized());

    assertEquals(0d, files.get(103L).raw());
    assertEquals(0.0, files.get(103L).normalized());
  }

  @Test
  void allUntouched() {
    List<FileSnapshot> snapshot =
        List.of(new FileSnapshot(101, "A", 99), new FileSnapshot(102, "B", 9));

    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(List.of(), snapshot, Set.of(ALICE, BOB));

    // p95 = 0 and max = 0 -> the "otherwise" branch of N_95
    assertEquals(0d, files.get(101L).raw());
    assertEquals(0.0, files.get(101L).normalized());
    assertEquals(0d, files.get(102L).raw());
    assertEquals(0.0, files.get(102L).normalized());
  }

  @Test
  void unknownContributorId() {
    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(baseFixture(), snapshotFixture(), Set.of(999L));

    assertEquals(3, files.size());
    for (MetricScore score : files.values()) {
      assertEquals(0d, score.raw());
      assertEquals(0.0, score.normalized());
    }
  }

  @Test
  void outputKeyedByFileRevisionIdInSnapshotOrder() {
    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(
            baseFixture(), snapshotFixture(), Set.of(ALICE, BOB));

    // Every snapshot file gets an entry, keyed by its revision id, in snapshot order.
    assertEquals(List.of(101L, 102L, 103L), List.copyOf(files.keySet()));
  }

  @Test
  void noStateLeakBetweenCalls() {
    Map<Long, MetricScore> first =
        SocialMetricsService.computeCommitActivity(
            baseFixture(), snapshotFixture(), Set.of(ALICE, BOB));
    Map<Long, MetricScore> second =
        SocialMetricsService.computeCommitActivity(
            baseFixture(), snapshotFixture(), Set.of(ALICE, BOB));

    assertEquals(first, second);
  }

  @Test
  void duplicateBaseRowSameFileAndContributor() {
    List<ContributorFileActivity> base =
        List.of(
            new ContributorFileActivity("A", ALICE, "alice", "alice", 2L, 0L),
            new ContributorFileActivity("A", ALICE, "alice", "alice", 3L, 0L));
    List<FileSnapshot> snapshot = List.of(new FileSnapshot(101, "A", 99));

    Map<Long, MetricScore> files =
        SocialMetricsService.computeCommitActivity(base, snapshot, Set.of(ALICE));

    // Documents merge-sum. The Cypher groups by (path, aid), so this shouldn't arise in practice.
    assertEquals(5d, files.get(101L).raw());
    assertEquals(1.0, files.get(101L).normalized());
  }
}
