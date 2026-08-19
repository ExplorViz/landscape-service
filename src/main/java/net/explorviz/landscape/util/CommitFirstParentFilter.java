package net.explorviz.landscape.util;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.ogm.Commit;

/** Keeps only commits reachable on a branch via first-parent links from the branch head. */
public final class CommitFirstParentFilter {

  private static final Comparator<Commit> COMMIT_ORDER =
      Comparator.comparing(Commit::getCommitDate, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Commit::getAuthorDate, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Commit::getHash);

  private CommitFirstParentFilter() {}

  /**
   * Returns commits that would appear on {@code branchName} with {@code git log --first-parent}:
   * walk backwards from the branch head following only {@link Commit#getFirstParentCommit()}.
   */
  public static List<Commit> filterToFirstParentOnly(final List<Commit> branchCommits) {
    if (branchCommits.size() <= 1) {
      return List.copyOf(branchCommits);
    }

    final Map<String, Commit> commitsByHash = HashMap.newHashMap(branchCommits.size());
    for (final Commit commit : branchCommits) {
      commitsByHash.put(commit.getHash(), commit);
    }

    final Set<String> reachableHashes = new HashSet<>();
    for (final Commit head : findBranchHeads(branchCommits, commitsByHash)) {
      Commit current = head;
      while (current != null) {
        if (commitsByHash.containsKey(current.getHash())
            && !reachableHashes.add(current.getHash())) {
          break;
        }
        current =
            current
                .getFirstParentCommit()
                .filter(parent -> !reachableHashes.contains(parent.getHash()))
                .orElse(null);
      }
    }

    return branchCommits.stream()
        .filter(commit -> reachableHashes.contains(commit.getHash()))
        .toList();
  }

  private static List<Commit> findBranchHeads(
      final List<Commit> branchCommits, final Map<String, Commit> commitsByHash) {

    final Set<String> firstParentHashesOnBranch = new HashSet<>();
    for (final Commit commit : branchCommits) {
      commit
          .getFirstParentCommit()
          .filter(parent -> commitsByHash.containsKey(parent.getHash()))
          .ifPresent(parent -> firstParentHashesOnBranch.add(parent.getHash()));
    }

    final List<Commit> tips =
        branchCommits.stream()
            .filter(commit -> !firstParentHashesOnBranch.contains(commit.getHash()))
            .toList();

    if (tips.isEmpty()) {
      return List.of(branchCommits.stream().max(COMMIT_ORDER).orElseThrow());
    }

    final Commit latestTip = tips.stream().max(COMMIT_ORDER).orElseThrow();
    return List.of(latestTip);
  }
}
