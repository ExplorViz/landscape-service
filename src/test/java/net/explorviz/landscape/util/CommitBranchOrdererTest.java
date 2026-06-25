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

    final Commit commit1 = commit("c1", branch, Instant.ofEpochMilli(3000));
    final Commit commit2 = commit("c2", branch, Instant.ofEpochMilli(1000));
    commit2.addParentCommit(commit1);

    final Commit commit3 = commit("c3", branch, Instant.ofEpochMilli(2000));
    commit3.addParentCommit(commit2);

    final List<Commit> ordered =
        CommitBranchOrderer.orderAlongBranch(List.of(commit3, commit1, commit2));

    assertEquals(List.of("c1", "c2", "c3"), ordered.stream().map(Commit::getHash).toList());
  }

  @Test
  void orderAlongBranch_startsAtBranchRootWhenParentIsOnAnotherBranch() {
    final Branch main = new Branch("main");
    final Branch feature = new Branch("feature");

    final Commit base = commit("base", main, Instant.ofEpochMilli(1000));
    final Commit featureTip = commit("feature-tip", feature, Instant.ofEpochMilli(2000));
    featureTip.addParentCommit(base);

    final List<Commit> ordered = CommitBranchOrderer.orderAlongBranch(List.of(featureTip));

    assertEquals(List.of("feature-tip"), ordered.stream().map(Commit::getHash).toList());
  }

  private static Commit commit(final String hash, final Branch branch, final Instant authorDate) {
    final Commit commit = new Commit(hash);
    commit.setBranch(branch);
    commit.setAuthorDate(authorDate);
    return commit;
  }
}
