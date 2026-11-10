package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.util.CommitBranchOrderer;
import net.explorviz.landscape.util.CommitFirstParentFilter;
import org.junit.jupiter.api.Test;

class CommitTreeReaderTest {

  @Test
  void resolvesParentsBeforeTheirRowsAndPreservesMetadata() {
    final Map<String, Commit> byHash = new HashMap<>();
    final Map<String, Branch> branches = new HashMap<>();
    final List<Commit> commits = new ArrayList<>();
    CommitTreeReader.addCommit(
        Map.of(
            "properties",
            Map.of(
                "hash",
                "merge",
                "commitDate",
                2000L,
                "authorDate",
                1000L,
                "hasAccumulatedMetrics",
                true,
                "metrics.fileCount",
                42L),
            "branch",
            "main",
            "parents",
            new Object[] {
              Map.of("hash", "root", "branch", "main", "firstParent", true),
              Map.of("hash", "side", "branch", "feature", "firstParent", false)
            }),
        byHash,
        branches,
        commits);
    CommitTreeReader.addCommit(
        Map.of("properties", Map.of("hash", "root"), "branch", "main", "parents", List.of()),
        byHash,
        branches,
        commits);

    final Commit merge = commits.getFirst();
    assertSame(commits.get(1), merge.getFirstParentCommit().orElseThrow());
    assertSame(merge.getBranch(), commits.get(1).getBranch());
    assertEquals("feature", merge.getMiscParentCommits().iterator().next().getBranch().getName());
    assertEquals(Instant.ofEpochMilli(2000), merge.getCommitDate());
    assertEquals(Instant.ofEpochMilli(1000), merge.getAuthorDate());
    assertEquals(Map.of("fileCount", 42.0), merge.getMetrics());
    assertTrue(merge.isHasAccumulatedMetrics());
    assertNull(commits.get(1).getCommitDate());
    assertTrue(commits.get(1).getMetrics().isEmpty());
    assertEquals(2, commits.size()); // Parent stubs are not emitted as repository commits.
  }

  @Test
  void readsAndOrdersFifteenThousandCommitsWithoutRecursingThroughEntities() {
    final Map<String, Commit> byHash = new HashMap<>();
    final Map<String, Branch> branches = new HashMap<>();
    final List<Commit> commits = new ArrayList<>();
    for (int index = 14999; index >= 0; index--) {
      CommitTreeReader.addCommit(
          Map.of(
              "properties",
              Map.of("hash", "commit" + index, "commitDate", (long) index),
              "branch",
              "main",
              "parents",
              index == 0
                  ? List.of()
                  : List.of(
                      Map.of(
                          "hash", "commit" + (index - 1), "branch", "main", "firstParent", true))),
          byHash,
          branches,
          commits);
    }
    final List<Commit> ordered =
        CommitFirstParentFilter.filterToFirstParentOnly(
            CommitBranchOrderer.orderAlongBranch(commits));
    assertEquals(15000, ordered.size());
    for (int index = 0; index < ordered.size(); index++) {
      assertEquals("commit" + index, ordered.get(index).getHash());
    }
  }
}
