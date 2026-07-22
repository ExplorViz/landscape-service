package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import org.junit.jupiter.api.Test;

class CommitBranchOrdererTest {

  @Test
  void orderAlongBranch_followsParentChildRelationship() {
    final Branch branch = new Branch("main");

    final Commit commit1 = commit("c1", branch, Instant.ofEpochSecond(1));
    final Commit commit2 = commit("c2", branch, Instant.ofEpochSecond(2));
    commit2.addParentCommit(commit1);

    final Commit commit3 = commit("c3", branch, Instant.ofEpochSecond(3));
    commit3.addParentCommit(commit2);

    final List<Commit> ordered =
        CommitBranchOrderer.orderAlongBranch(List.of(commit3, commit1, commit2));

    assertEquals(List.of("c1", "c2", "c3"), ordered.stream().map(Commit::getHash).toList());
  }

  @Test
  void orderAlongBranch_ordersMergeCommitsAfterBothParents() {
    final Branch branch = new Branch("main");

    final Commit base = commit("base", branch, Instant.ofEpochSecond(1));
    final Commit feature = commit("feature", branch, Instant.ofEpochSecond(2));
    feature.addParentCommit(base);
    final Commit mainTip = commit("main-tip", branch, Instant.ofEpochSecond(3));
    mainTip.addParentCommit(base);
    final Commit merge = commit("merge", branch, Instant.ofEpochSecond(4));
    merge.addParentCommit(mainTip);
    merge.addParentCommit(feature);

    final List<Commit> ordered =
        CommitBranchOrderer.orderAlongBranch(List.of(merge, feature, mainTip, base));

    assertEquals(
        List.of("base", "feature", "main-tip", "merge"),
        ordered.stream().map(Commit::getHash).toList());
  }

  @Test
  void orderAlongBranch_startsAtBranchRootWhenParentIsOnAnotherBranch() {
    final Branch main = new Branch("main");
    final Branch feature = new Branch("feature");

    final Commit base = commit("base", main, Instant.ofEpochSecond(1));
    final Commit featureTip = commit("feature-tip", feature, Instant.ofEpochSecond(2));
    featureTip.addParentCommit(base);

    final List<Commit> ordered = CommitBranchOrderer.orderAlongBranch(List.of(featureTip));

    assertEquals(List.of("feature-tip"), ordered.stream().map(Commit::getHash).toList());
  }

  private static Commit commit(final String hash, final Branch branch, final Instant commitDate) {
    final Commit commit = new Commit(hash);
    commit.setBranch(branch);
    commit.setCommitDate(commitDate);
    commit.setAuthorDate(commitDate);
    return commit;
  }
}
