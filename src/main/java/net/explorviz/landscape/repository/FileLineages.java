package net.explorviz.landscape.repository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps the paths a file had in the past to the path it has in one commit, the snapshot commit.
 *
 * <p>A path is part of a file revision's identity, so everything that is aggregated per path over
 * several commits stops at the commit that renamed the file. The lineages resolved here allow such
 * aggregations to attribute a change to the file that is shown for the snapshot commit, no matter
 * which path the file had when it was changed.
 *
 * <p>A path alone does not identify a file over time: after a file was moved away, another file can
 * be created at its old path. A lineage therefore consists of segments, each one being a path
 * together with the range of commits in which that path belonged to the file. Commits are compared
 * by their position in a topological order of the whole repository instead of their dates, since
 * rebased commits frequently share a date.
 *
 * <p>Only renames made by the snapshot commit or one of its ancestors are considered. A rename on a
 * branch that is not merged into the snapshot commit says nothing about the files of that commit.
 */
public final class FileLineages {

  /** Lineages of a repository without renames: every path only ever stands for itself. */
  public static final FileLineages NONE = new FileLineages(Map.of(), Map.of());

  private final Map<String, List<Segment>> segmentsByPath;
  private final Map<Long, Integer> orderByCommitId;

  private FileLineages(
      final Map<String, List<Segment>> segmentsByPath, final Map<Long, Integer> orderByCommitId) {
    this.segmentsByPath = segmentsByPath;
    this.orderByCommitId = orderByCommitId;
  }

  /**
   * One commit to parent edge of a repository.
   *
   * @param commitId internal id of the commit
   * @param hash hash of the commit
   * @param commitDate commit date used to order commits that are not related by ancestry
   * @param parentId internal id of one parent, {@code null} for a commit without parents
   */
  public record CommitLink(long commitId, String hash, long commitDate, Long parentId) {}

  /**
   * A file that was moved by a commit.
   *
   * @param commitId internal id of the renaming commit
   * @param oldPath path of the file in the first parent of the commit
   * @param newPath path of the file in the commit
   */
  public record RenameEvent(long commitId, String oldPath, String newPath) {}

  /** Commits {@code fromOrder..toOrder} in which a path belonged to {@code currentPath}. */
  private record Segment(int fromOrder, int toOrder, String currentPath) {}

  private record OrderedRename(String oldPath, int order) {}

  /** A path and the commits up to which renames are taken into account for it. */
  private record PendingPath(String path, int lastRenameOrder, int toOrder) {}

  /** First and last commit that renamed a file from one path to another. */
  private record RenameRange(int firstOrder, int lastOrder) {
    private RenameRange merge(final RenameRange other) {
      return new RenameRange(
          Math.min(firstOrder, other.firstOrder), Math.max(lastOrder, other.lastOrder));
    }
  }

  /** The renames in the history of the snapshot commit by the paths they lead to and from. */
  private record RenameIndex(
      Map<String, List<OrderedRename>> intoPath, Map<String, NavigableSet<Integer>> outOfPath) {}

  /**
   * Resolves the lineages of the files of one commit.
   *
   * @param commitLinks all commits of the repository with their parents
   * @param renames all renames of the repository
   * @param snapshotCommitHash hash of the commit whose files the lineages are resolved for
   * @param snapshotPaths paths of the files of that commit
   */
  public static FileLineages resolve(
      final List<CommitLink> commitLinks,
      final List<RenameEvent> renames,
      final String snapshotCommitHash,
      final Collection<String> snapshotPaths) {
    if (renames.isEmpty()) {
      return NONE;
    }

    final Map<Long, Set<Long>> parentsByCommitId = new HashMap<>();
    final Map<Long, Long> dateByCommitId = new HashMap<>();
    Long snapshotCommitId = null;
    for (final CommitLink link : commitLinks) {
      final Set<Long> parents =
          parentsByCommitId.computeIfAbsent(link.commitId(), id -> new HashSet<>());
      if (link.parentId() != null) {
        parents.add(link.parentId());
      }
      dateByCommitId.put(link.commitId(), link.commitDate());
      if (snapshotCommitHash.equals(link.hash())) {
        snapshotCommitId = link.commitId();
      }
    }
    if (snapshotCommitId == null) {
      return NONE;
    }

    final Map<Long, Integer> orderByCommitId = topologicalOrder(parentsByCommitId, dateByCommitId);
    final RenameIndex renameIndex =
        indexRenames(renames, orderByCommitId, ancestorsOf(snapshotCommitId, parentsByCommitId));
    if (renameIndex.intoPath().isEmpty()) {
      return NONE;
    }

    final Map<String, List<Segment>> segmentsByPath = new HashMap<>();
    renameIndex.intoPath().keySet().forEach(path -> segmentsByPath.put(path, new ArrayList<>()));
    renameIndex.outOfPath().keySet().forEach(path -> segmentsByPath.put(path, new ArrayList<>()));
    for (final String snapshotPath : new HashSet<>(snapshotPaths)) {
      if (segmentsByPath.containsKey(snapshotPath)) {
        collectSegments(snapshotPath, renameIndex, segmentsByPath);
      }
    }
    return new FileLineages(segmentsByPath, orderByCommitId);
  }

  /** Indexes the renames made by the given commits, which are the only ones that matter. */
  private static RenameIndex indexRenames(
      final List<RenameEvent> renames,
      final Map<Long, Integer> orderByCommitId,
      final Set<Long> commitIds) {
    final Map<String, List<OrderedRename>> intoPath = new HashMap<>();
    final Map<String, NavigableSet<Integer>> outOfPath = new HashMap<>();
    for (final RenameEvent rename : renames) {
      final Integer order = orderByCommitId.get(rename.commitId());
      if (order != null && commitIds.contains(rename.commitId()) && changesPath(rename)) {
        intoPath
            .computeIfAbsent(rename.newPath(), path -> new ArrayList<>())
            .add(new OrderedRename(rename.oldPath(), order));
        outOfPath.computeIfAbsent(rename.oldPath(), path -> new TreeSet<>()).add(order);
      }
    }
    return new RenameIndex(intoPath, outOfPath);
  }

  private static boolean changesPath(final RenameEvent rename) {
    return rename.oldPath() != null
        && rename.newPath() != null
        && !rename.oldPath().equals(rename.newPath());
  }

  /** Whether no file of the snapshot commit can have had another path. */
  public boolean isEmpty() {
    return segmentsByPath.isEmpty();
  }

  /**
   * Paths that are the old or new side of a rename. Changes at any other path belong to the file
   * that has this path in the snapshot commit, if there is one.
   */
  public Set<String> trackedPaths() {
    return segmentsByPath.keySet();
  }

  /**
   * Resolves the files of the snapshot commit that a change at a {@link #trackedPaths() tracked
   * path} belongs to.
   *
   * @param path path that was changed
   * @param commitId internal id of the commit that changed it
   * @return paths in the snapshot commit; empty if the changed file is not part of that commit
   */
  public Set<String> currentPathsOf(final String path, final long commitId) {
    final List<Segment> segments = segmentsByPath.get(path);
    final Integer order = orderByCommitId.get(commitId);
    if (segments == null || order == null) {
      return Set.of();
    }
    final Set<String> currentPaths = new LinkedHashSet<>();
    for (final Segment segment : segments) {
      if (segment.fromOrder() <= order && order <= segment.toOrder()) {
        currentPaths.add(segment.currentPath());
      }
    }
    return currentPaths;
  }

  /**
   * Walks from a file of the snapshot commit back through the renames that led to its path.
   *
   * <p>The renames into a path that happened since the path was last moved away from all concern
   * the file that has the path now. There can be several of them for one move: a rename on a branch
   * is reported again by the commit that merges the branch. The file then owns its new path from
   * the first of these renames on and keeps its old path until the last one, which covers the
   * changes made to the old path on the other side of the merge.
   */
  private static void collectSegments(
      final String snapshotPath,
      final RenameIndex renameIndex,
      final Map<String, List<Segment>> segmentsByPath) {
    final Deque<PendingPath> pending = new ArrayDeque<>();
    final Set<PendingPath> visited = new HashSet<>();
    pending.add(new PendingPath(snapshotPath, Integer.MAX_VALUE, Integer.MAX_VALUE));

    while (!pending.isEmpty()) {
      final PendingPath current = pending.poll();
      if (!visited.add(current)) {
        continue;
      }

      final NavigableSet<Integer> movedAway = renameIndex.outOfPath().get(current.path());
      final Integer lastMovedAway =
          movedAway == null ? null : movedAway.floor(current.lastRenameOrder());
      final Map<String, RenameRange> renamesByOldPath =
          renamesIntoPath(current, lastMovedAway, renameIndex);

      // Without a rename the file was created at this path, possibly after the previous file at
      // the path was moved away.
      final int fromOrder =
          renamesByOldPath.values().stream()
              .mapToInt(RenameRange::firstOrder)
              .min()
              .orElse(lastMovedAway == null ? Integer.MIN_VALUE : lastMovedAway);
      segmentsByPath
          .get(current.path())
          .add(new Segment(fromOrder, current.toOrder(), snapshotPath));

      // the renaming commits themselves no longer change the old path
      renamesByOldPath.forEach(
          (oldPath, range) ->
              pending.add(new PendingPath(oldPath, range.firstOrder() - 1, range.lastOrder() - 1)));
    }
  }

  /** Collects the renames that gave the path to the file that has it at the given time. */
  private static Map<String, RenameRange> renamesIntoPath(
      final PendingPath current, final Integer lastMovedAway, final RenameIndex renameIndex) {
    final Map<String, RenameRange> renamesByOldPath = new HashMap<>();
    for (final OrderedRename rename :
        renameIndex.intoPath().getOrDefault(current.path(), List.of())) {
      if (rename.order() <= current.lastRenameOrder()
          && (lastMovedAway == null || rename.order() >= lastMovedAway)) {
        renamesByOldPath.merge(
            rename.oldPath(), new RenameRange(rename.order(), rename.order()), RenameRange::merge);
      }
    }
    return renamesByOldPath;
  }

  /**
   * Numbers all commits so that every commit comes after its parents. Commits that are not ordered
   * by ancestry are ordered by date.
   */
  private static Map<Long, Integer> topologicalOrder(
      final Map<Long, Set<Long>> parentsByCommitId, final Map<Long, Long> dateByCommitId) {
    final Map<Long, List<Long>> childrenByCommitId = new HashMap<>();
    final Map<Long, Integer> openParentCount = new HashMap<>();
    final Queue<Long> ready =
        new PriorityQueue<>(
            Comparator.<Long, Long>comparing(dateByCommitId::get)
                .thenComparing(Comparator.naturalOrder()));

    parentsByCommitId.forEach(
        (commitId, parents) -> {
          int knownParents = 0;
          for (final Long parentId : parents) {
            if (parentsByCommitId.containsKey(parentId)) {
              childrenByCommitId.computeIfAbsent(parentId, id -> new ArrayList<>()).add(commitId);
              knownParents++;
            }
          }
          if (knownParents == 0) {
            ready.add(commitId);
          } else {
            openParentCount.put(commitId, knownParents);
          }
        });

    final Map<Long, Integer> orderByCommitId = new HashMap<>();
    while (!ready.isEmpty()) {
      final Long commitId = ready.poll();
      orderByCommitId.put(commitId, orderByCommitId.size());
      for (final Long childId : childrenByCommitId.getOrDefault(commitId, List.of())) {
        if (openParentCount.merge(childId, -1, Integer::sum) == 0) {
          ready.add(childId);
        }
      }
    }
    return orderByCommitId;
  }

  private static Set<Long> ancestorsOf(
      final long commitId, final Map<Long, Set<Long>> parentsByCommitId) {
    final Set<Long> ancestors = new HashSet<>();
    final Deque<Long> pending = new ArrayDeque<>();
    pending.add(commitId);
    while (!pending.isEmpty()) {
      final Long current = pending.poll();
      if (ancestors.add(current)) {
        pending.addAll(parentsByCommitId.getOrDefault(current, Set.of()));
      }
    }
    return ancestors;
  }
}
