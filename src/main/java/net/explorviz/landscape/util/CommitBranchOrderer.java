package net.explorviz.landscape.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.ogm.Commit;

/** Orders commits along a single branch by parent-child relationships (root to tip). */
public final class CommitBranchOrderer {

  private CommitBranchOrderer() {}

  /**
   * Returns the given commits in branch order: each commit appears after its parent on the same
   * branch. Commits without a parent in the set are treated as branch roots.
   */
  public static List<Commit> orderAlongBranch(final List<Commit> branchCommits) {
    if (branchCommits.size() <= 1) {
      return List.copyOf(branchCommits);
    }

    final Map<String, Commit> commitsByHash = HashMap.newHashMap(branchCommits.size());
    for (final Commit commit : branchCommits) {
      commitsByHash.put(commit.getHash(), commit);
    }

    final Map<String, List<Commit>> childrenByParentHash = new HashMap<>();
    final List<Commit> roots = new ArrayList<>();

    for (final Commit commit : branchCommits) {
      final Commit parentOnBranch = findParentOnBranch(commit, commitsByHash);
      if (parentOnBranch == null) {
        roots.add(commit);
      } else {
        childrenByParentHash
            .computeIfAbsent(parentOnBranch.getHash(), ignored -> new ArrayList<>())
            .add(commit);
      }
    }

    roots.sort(
        Comparator.comparing(
            Commit::getAuthorDate, Comparator.nullsLast(Comparator.naturalOrder())));
    for (final List<Commit> children : childrenByParentHash.values()) {
      children.sort(
          Comparator.comparing(
              Commit::getAuthorDate, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    final List<Commit> ordered = new ArrayList<>(branchCommits.size());
    final Set<String> visited = new HashSet<>();
    for (final Commit root : roots) {
      appendDepthFirst(root, childrenByParentHash, visited, ordered);
    }

    for (final Commit commit : branchCommits) {
      if (!visited.contains(commit.getHash())) {
        ordered.add(commit);
      }
    }

    return ordered;
  }

  private static Commit findParentOnBranch(
      final Commit commit, final Map<String, Commit> commitsByHash) {
    Commit parentOnBranch = null;
    for (final Commit parent : commit.getParentCommits()) {
      if (commitsByHash.containsKey(parent.getHash())
          && (parentOnBranch == null || isAfter(parent, parentOnBranch))) {
        parentOnBranch = parent;
      }
    }
    return parentOnBranch;
  }

  private static boolean isAfter(final Commit later, final Commit earlier) {
    return later.getAuthorDate() != null
        && (earlier.getAuthorDate() == null
            || later.getAuthorDate().isAfter(earlier.getAuthorDate()));
  }

  private static void appendDepthFirst(
      final Commit commit,
      final Map<String, List<Commit>> childrenByParentHash,
      final Set<String> visited,
      final List<Commit> ordered) {
    if (!visited.add(commit.getHash())) {
      return;
    }
    ordered.add(commit);
    for (final Commit child : childrenByParentHash.getOrDefault(commit.getHash(), List.of())) {
      appendDepthFirst(child, childrenByParentHash, visited, ordered);
    }
  }
}
