package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import org.junit.jupiter.api.Test;

class CommitFirstParentFilterTest {

  @Test
  void filterToFirstParentOnly_keepsLinearHistory() {
    final Branch branch = new Branch("main");

    final Commit commit1 = commit("c1", branch, Instant.ofEpochSecond(1));
    final Commit commit2 = commit("c2", branch, Instant.ofEpochSecond(2));
    commit2.setFirstParentCommit(commit1);

    final Commit commit3 = commit("c3", branch, Instant.ofEpochSecond(3));
    commit3.setFirstParentCommit(commit2);

    final List<Commit> filtered =
        CommitFirstParentFilter.filterToFirstParentOnly(List.of(commit1, commit2, commit3));

    assertEquals(List.of("c1", "c2", "c3"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void filterToFirstParentOnly_excludesMergedSideBranchCommits() {
    final Branch branch = new Branch("main");

    final Commit base = commit("base", branch, Instant.ofEpochSecond(1));
    final Commit feature = commit("feature", branch, Instant.ofEpochSecond(2));
    feature.setFirstParentCommit(base);
    final Commit mainTip = commit("main-tip", branch, Instant.ofEpochSecond(3));
    mainTip.setFirstParentCommit(base);
    final Commit merge = commit("merge", branch, Instant.ofEpochSecond(4));
    merge.setFirstParentCommit(mainTip);
    merge.addMiscParentCommit(feature);

    final List<Commit> filtered =
        CommitFirstParentFilter.filterToFirstParentOnly(List.of(base, feature, mainTip, merge));

    assertEquals(
        List.of("base", "main-tip", "merge"), filtered.stream().map(Commit::getHash).toList());
  }

  @Test
  void filterToFirstParentOnly_keepsFeatureBranchTipWhenParentIsOnAnotherBranch() {
    final Branch main = new Branch("main");
    final Branch feature = new Branch("feature");

    final Commit base = commit("base", main, Instant.ofEpochSecond(1));
    final Commit featureTip = commit("feature-tip", feature, Instant.ofEpochSecond(2));
    featureTip.setFirstParentCommit(base);

    final List<Commit> filtered =
        CommitFirstParentFilter.filterToFirstParentOnly(List.of(featureTip));

    assertEquals(List.of("feature-tip"), filtered.stream().map(Commit::getHash).toList());
  }

  private static Commit commit(final String hash, final Branch branch, final Instant commitDate) {
    final Commit commit = new Commit(hash);
    commit.setBranch(branch);
    commit.setCommitDate(commitDate);
    commit.setAuthorDate(commitDate);
    return commit;
  }
}
