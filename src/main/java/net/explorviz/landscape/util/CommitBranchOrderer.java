package net.explorviz.landscape.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import net.explorviz.landscape.ogm.Commit;

/** Orders commits along a single branch in git-compatible topological order (root to tip). */
public final class CommitBranchOrderer {

  private static final Comparator<Commit> COMMIT_ORDER =
      Comparator.comparing(Commit::getCommitDate, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Commit::getAuthorDate, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Commit::getHash);

  private CommitBranchOrderer() {}

  /**
   * Returns commits in branch order: every parent appears before its children, matching {@code git
   * rev-list --reverse} when parent links are complete.
   */
  @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
  public static List<Commit> orderAlongBranch(final List<Commit> branchCommits) {
    if (branchCommits.size() <= 1) {
      return List.copyOf(branchCommits);
    }

    final Map<String, Commit> commitsByHash = HashMap.newHashMap(branchCommits.size());
    for (final Commit commit : branchCommits) {
      commitsByHash.put(commit.getHash(), commit);
    }

    final Map<String, List<Commit>> childrenByParentHash = new HashMap<>();
    final Map<String, Integer> inDegreeByHash = HashMap.newHashMap(branchCommits.size());

    for (final Commit commit : branchCommits) {
      inDegreeByHash.put(commit.getHash(), 0);
    }

    for (final Commit commit : branchCommits) {
      int parentsOnBranch = 0;
      for (final Commit parent : commit.getParentCommits()) {
        if (!commitsByHash.containsKey(parent.getHash())) {
          continue;
        }
        parentsOnBranch++;
        childrenByParentHash
            .computeIfAbsent(parent.getHash(), ignored -> new ArrayList<>())
            .add(commit);
      }
      inDegreeByHash.put(commit.getHash(), parentsOnBranch);
    }

    final Queue<Commit> ready = new PriorityQueue<>(COMMIT_ORDER);
    for (final Commit commit : branchCommits) {
      if (inDegreeByHash.get(commit.getHash()) == 0) {
        ready.add(commit);
      }
    }

    final List<Commit> ordered = new ArrayList<>(branchCommits.size());
    while (!ready.isEmpty()) {
      final Commit commit = ready.poll();
      ordered.add(commit);
      for (final Commit child : childrenByParentHash.getOrDefault(commit.getHash(), List.of())) {
        final int nextInDegree = inDegreeByHash.merge(child.getHash(), -1, Integer::sum);
        if (nextInDegree == 0) {
          ready.add(child);
        }
      }
    }

    if (ordered.size() < branchCommits.size()) {
      final Set<String> orderedHashes = new HashSet<>();
      ordered.forEach(commit -> orderedHashes.add(commit.getHash()));
      branchCommits.stream()
          .filter(commit -> !orderedHashes.contains(commit.getHash()))
          .sorted(COMMIT_ORDER)
          .forEach(ordered::add);
    }

    return ordered;
  }
}
