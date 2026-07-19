package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.metrics.CommitActivity;
import net.explorviz.landscape.repository.metrics.SocialMetric.MetricInput;
import org.junit.jupiter.api.Test;

public class CommitActivityTest {

  private static final long ALICE = 1L;
  private static final long BOB = 2L;

  private final CommitActivity commitActivity = new CommitActivity();

  private Map<Long, MetricScore> compute(
      final List<ContributorFileActivity> fileActivities,
      final List<FileSnapshot> snapshotFiles,
      final Set<Long> contributorIds,
      final Set<Long> coreIds) {
    return commitActivity.computeMetric(
        new MetricInput(
            fileActivities,
            snapshotFiles,
            contributorIds,
            coreIds,
            new SocialMetricsRepository.RepoTimeBounds(0, 0)));
  }

  /** A: alice 2 + bob 1 = 3, B: alice 1. */
  private static List<ContributorFileActivity> fileActivitiesFixture() {
    return List.of(
        new ContributorFileActivity("A", ALICE, "alice", "alice", 2L, 0L),
        new ContributorFileActivity("A", BOB, "bob", "bob", 1L, 0L),
        new ContributorFileActivity("B", ALICE, "alice", "alice", 1L, 0L));
  }

  /** 101 -> A, 102 -> B, 103 -> C. */
  private static List<FileSnapshot> snapshotFilesFixture() {
    return List.of(
        new FileSnapshot(101, "A", 99),
        new FileSnapshot(102, "B", 9),
        new FileSnapshot(103, "C", 999));
  }

  @Test
  void basicMultiFile() {
    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(
            fileActivitiesFixture(),
            snapshotFilesFixture(),
            Set.of(ALICE, BOB),
            Set.of(ALICE, BOB));

    // V = {3, 1, 0} -> p95 = 3
    assertEquals(3, scoreByFileRevisionId.size());

    assertEquals(3d, scoreByFileRevisionId.get(101L).raw());
    assertEquals(1.0, scoreByFileRevisionId.get(101L).normalized());

    assertEquals(1d, scoreByFileRevisionId.get(102L).raw());
    assertEquals(1 / 3d, scoreByFileRevisionId.get(102L).normalized());

    assertEquals(0d, scoreByFileRevisionId.get(103L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(103L).normalized());
  }

  @Test
  void contributorSubset() {
    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(BOB), Set.of(BOB));

    // V = {1, 0, 0} -> p95 = 1
    assertEquals(1d, scoreByFileRevisionId.get(101L).raw());
    assertEquals(1.0, scoreByFileRevisionId.get(101L).normalized());

    assertEquals(0d, scoreByFileRevisionId.get(102L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(102L).normalized());

    assertEquals(0d, scoreByFileRevisionId.get(103L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(103L).normalized());
  }

  @Test
  void emptyContributorsMeansAll() {
    Map<Long, MetricScore> all =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(ALICE, BOB), Set.of(BOB));
    Map<Long, MetricScore> empty =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(), Set.of(ALICE, BOB));

    assertEquals(all, empty);
  }

  @Test
  void fileInBaseButNotSnapshotExcluded() {
    List<ContributorFileActivity> fileActivities =
        List.of(
            new ContributorFileActivity("A", ALICE, "alice", "alice", 2L, 0L),
            new ContributorFileActivity("A", BOB, "bob", "bob", 1L, 0L),
            new ContributorFileActivity("B", ALICE, "alice", "alice", 1L, 0L),
            new ContributorFileActivity("D", ALICE, "alice", "alice", 5L, 0L));
    List<FileSnapshot> snapshotFiles =
        List.of(new FileSnapshot(101, "A", 99), new FileSnapshot(102, "B", 9));

    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(fileActivities, snapshotFiles, Set.of(ALICE, BOB), Set.of(BOB));

    assertEquals(List.of(101L, 102L), List.copyOf(scoreByFileRevisionId.keySet()));

    // V = {3, 1} -> p95 = 3. If D's 5 leaked in, V = {3, 1, 5} -> p95 = 5 and A would be 0.6.
    assertEquals(3d, scoreByFileRevisionId.get(101L).raw());
    assertEquals(1.0, scoreByFileRevisionId.get(101L).normalized());

    assertEquals(1d, scoreByFileRevisionId.get(102L).raw());
    assertEquals(1 / 3d, scoreByFileRevisionId.get(102L).normalized());
  }

  @Test
  void untouchedSnapshotFileScoresZeroAndIsInMultiset() {
    List<ContributorFileActivity> fileActivities =
        List.of(new ContributorFileActivity("A", ALICE, "alice", "alice", 10L, 0L));

    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(fileActivities, snapshotFilesFixture(), Set.of(ALICE, BOB), Set.of(ALICE, BOB));

    assertEquals(3, scoreByFileRevisionId.size());
    assertTrue(scoreByFileRevisionId.containsKey(102L));
    assertTrue(scoreByFileRevisionId.containsKey(103L));

    assertEquals(10d, scoreByFileRevisionId.get(101L).raw());
    assertEquals(1.0, scoreByFileRevisionId.get(101L).normalized());

    assertEquals(0d, scoreByFileRevisionId.get(102L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(102L).normalized());

    assertEquals(0d, scoreByFileRevisionId.get(103L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(103L).normalized());
  }

  @Test
  void allUntouched() {
    List<FileSnapshot> snapshotFiles =
        List.of(new FileSnapshot(101, "A", 99), new FileSnapshot(102, "B", 9));

    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(List.of(), snapshotFiles, Set.of(ALICE, BOB), Set.of(BOB));

    // p95 = 0 and max = 0 -> the "otherwise" branch of N_95
    assertEquals(0d, scoreByFileRevisionId.get(101L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(101L).normalized());
    assertEquals(0d, scoreByFileRevisionId.get(102L).raw());
    assertEquals(0.0, scoreByFileRevisionId.get(102L).normalized());
  }

  @Test
  void unknownContributorGetId() {
    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(999L), Set.of(ALICE, BOB));

    assertEquals(3, scoreByFileRevisionId.size());
    for (MetricScore score : scoreByFileRevisionId.values()) {
      assertEquals(0d, score.raw());
      assertEquals(0.0, score.normalized());
    }
  }

  @Test
  void outputKeyedByFileRevisionGetIdInSnapshotOrder() {
    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(ALICE, BOB), Set.of(BOB));

    // Every snapshot file gets an entry, keyed by its revision id, in snapshot order.
    assertEquals(List.of(101L, 102L, 103L), List.copyOf(scoreByFileRevisionId.keySet()));
  }

  @Test
  void noStateLeakBetweenCalls() {
    Map<Long, MetricScore> first =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(ALICE, BOB), Set.of(BOB));
    Map<Long, MetricScore> second =
        compute(fileActivitiesFixture(), snapshotFilesFixture(), Set.of(ALICE, BOB), Set.of(BOB));

    assertEquals(first, second);
  }

  @Test
  void duplicateBaseRowSameFileAndContributor() {
    List<ContributorFileActivity> fileActivities =
        List.of(
            new ContributorFileActivity("A", ALICE, "alice", "alice", 2L, 0L),
            new ContributorFileActivity("A", ALICE, "alice", "alice", 3L, 0L));
    List<FileSnapshot> snapshotFiles = List.of(new FileSnapshot(101, "A", 99));

    Map<Long, MetricScore> scoreByFileRevisionId =
        compute(fileActivities, snapshotFiles, Set.of(ALICE), Set.of(BOB));

    // Documents merge-sum. The Cypher groups by (path, aid), so this shouldn't arise in practice.
    assertEquals(5d, scoreByFileRevisionId.get(101L).raw());
    assertEquals(1.0, scoreByFileRevisionId.get(101L).normalized());
  }
}
