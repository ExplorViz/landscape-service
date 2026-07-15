package net.explorviz.landscape;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Set;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.repository.SocialMetricsService;
import net.explorviz.landscape.repository.SocialMetricsService.FileScore;
import org.junit.jupiter.api.Test;

public class SocialMetricsServiceTest {

  @Inject SocialMetricsService metricsService;

  @Test
  void basicMultiFile() {
    List<ContributorFileActivity> base =
        List.of(
            new ContributorFileActivity("A", 1, "alice", "alice", 2L, 0L),
            new ContributorFileActivity("A", 2, "bob", "bob", 1L, 0L),
            new ContributorFileActivity("B", 1, "alice", "alice", 1L, 0L));
    List<FileSnapshot> snapshot =
        List.of(
            new FileSnapshot(101, "A", 99),
            new FileSnapshot(102, "B", 9),
            new FileSnapshot(103, "C", 999));

    Set<Long> contributorIds = Set.of(1L, 2L);

    List<FileScore> files =
        SocialMetricsService.computeCommitActivity(base, snapshot, contributorIds);

    assertEquals(3, files.size());
    assertEquals(3, files.get(0).rawScore());
  }
}
