package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.explorviz.landscape.repository.PendingMetricsCommitTracker.TrackedCommit;
import org.junit.jupiter.api.Test;

class PendingMetricsCommitTrackerTest {

  private final PendingMetricsCommitTracker tracker = new PendingMetricsCommitTracker();

  @Test
  void unknownRepositoryIsNotTracked() {
    assertTrue(tracker.find("token", "repo").isEmpty());
  }

  @Test
  void tracksOnlyTheLatestCommitOfARepository() {
    tracker.track("token", "repo", "c1");
    tracker.track("token", "repo", "c2");
    tracker.track("token", "other-repo", "c9");

    assertEquals(new TrackedCommit("c2", false), tracker.find("token", "repo").orElseThrow());
    assertEquals(new TrackedCommit("c9", false), tracker.find("token", "other-repo").orElseThrow());
  }

  @Test
  void accumulatedCommitStaysTrackedUntilItIsReplaced() {
    tracker.track("token", "repo", "c1");
    tracker.markAccumulated("token", "repo", "c1");

    assertTrue(tracker.find("token", "repo").orElseThrow().accumulated());

    tracker.track("token", "repo", "c2");

    assertEquals(new TrackedCommit("c2", false), tracker.find("token", "repo").orElseThrow());
  }

  @Test
  void accumulatingAnotherCommitDoesNotAffectTheTrackedOne() {
    tracker.track("token", "repo", "c2");
    tracker.markAccumulated("token", "repo", "c1");
    tracker.markAccumulated("token", "untracked-repo", "c1");

    assertFalse(tracker.find("token", "repo").orElseThrow().accumulated());
    assertTrue(tracker.find("token", "untracked-repo").isEmpty());
  }

  @Test
  void invalidateReleasesTheRepository() {
    tracker.track("token", "repo", "c1");
    tracker.track("token", "other-repo", "c2");

    tracker.invalidate("token", "repo");

    assertTrue(tracker.find("token", "repo").isEmpty());
    assertTrue(tracker.find("token", "other-repo").isPresent());
  }

  @Test
  void invalidateLandscapeReleasesAllOfItsRepositories() {
    tracker.track("token", "repo", "c1");
    tracker.track("token", "other-repo", "c2");
    tracker.track("other-token", "repo", "c3");

    tracker.invalidateLandscape("token");

    assertTrue(tracker.find("token", "repo").isEmpty());
    assertTrue(tracker.find("token", "other-repo").isEmpty());
    assertTrue(tracker.find("other-token", "repo").isPresent());
  }
}
