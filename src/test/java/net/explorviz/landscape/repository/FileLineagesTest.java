package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.repository.FileLineages.CommitLink;
import net.explorviz.landscape.repository.FileLineages.RenameEvent;
import net.explorviz.landscape.repository.SocialMetricsRepository.CommitFileActivity;
import net.explorviz.landscape.repository.SocialMetricsRepository.MergedPrStats;
import net.explorviz.landscape.repository.SocialMetricsRepository.PullRequestFileChange;
import org.junit.jupiter.api.Test;

class FileLineagesTest {

  private static final String A = "src/A.java";
  private static final String B = "src/B.java";
  private static final String C = "src/C.java";
  private static final String OTHER = "src/Other.java";

  private final List<CommitLink> commits = new ArrayList<>();
  private final List<RenameEvent> renames = new ArrayList<>();

  @Test
  void repositoryWithoutRenamesHasNoLineages() {
    commit(1);
    commit(2, 1);

    assertSame(FileLineages.NONE, lineagesAt(2, A));
    assertTrue(FileLineages.NONE.isEmpty());
  }

  @Test
  void changesBeforeRenameBelongToTheNewPath() {
    commit(1);
    commit(2, 1);
    rename(3, 2, A, B);
    commit(4, 3);

    final FileLineages lineages = lineagesAt(4, B, OTHER);

    assertEquals(Set.of(A, B), lineages.trackedPaths());
    assertEquals(Set.of(B), lineages.currentPathsOf(A, 1));
    assertEquals(Set.of(B), lineages.currentPathsOf(A, 2));
    assertEquals(Set.of(B), lineages.currentPathsOf(B, 3));
    assertEquals(Set.of(B), lineages.currentPathsOf(B, 4));
  }

  @Test
  void followsSeveralRenames() {
    commit(1);
    rename(2, 1, A, B);
    commit(3, 2);
    rename(4, 3, B, C);

    final FileLineages lineages = lineagesAt(4, C);

    assertEquals(Set.of(C), lineages.currentPathsOf(A, 1));
    assertEquals(Set.of(C), lineages.currentPathsOf(B, 2));
    assertEquals(Set.of(C), lineages.currentPathsOf(B, 3));
    assertEquals(Set.of(C), lineages.currentPathsOf(C, 4));
  }

  @Test
  void fileCreatedAtAnAbandonedPathIsADifferentFile() {
    commit(1);
    rename(2, 1, A, B);
    commit(3, 2); // adds a new file at A

    final FileLineages lineages = lineagesAt(3, A, B);

    assertEquals(Set.of(B), lineages.currentPathsOf(A, 1));
    assertEquals(Set.of(B), lineages.currentPathsOf(B, 2));
    assertEquals(Set.of(A), lineages.currentPathsOf(A, 3));
  }

  @Test
  void fileCreatedAtAnAbandonedPathByTheRenamingCommitIsADifferentFile() {
    commit(1);
    rename(2, 1, A, B); // also adds a new file at A

    final FileLineages lineages = lineagesAt(2, A, B);

    assertEquals(Set.of(B), lineages.currentPathsOf(A, 1));
    assertEquals(Set.of(A), lineages.currentPathsOf(A, 2));
    assertEquals(Set.of(B), lineages.currentPathsOf(B, 2));
  }

  @Test
  void fileThatPreviouslyHadTheNewPathIsADifferentFile() {
    commit(1); // B exists and is deleted later on
    commit(2, 1);
    rename(3, 2, A, B);

    final FileLineages lineages = lineagesAt(3, B);

    assertEquals(Set.of(), lineages.currentPathsOf(B, 1));
    assertEquals(Set.of(B), lineages.currentPathsOf(A, 1));
    assertEquals(Set.of(B), lineages.currentPathsOf(B, 3));
  }

  @Test
  void fileRenamedBackAndForthKeepsItsWholeHistory() {
    commit(1);
    rename(2, 1, A, B);
    rename(3, 2, B, A);
    commit(4, 3);

    final FileLineages lineages = lineagesAt(4, A);

    assertEquals(Set.of(A), lineages.currentPathsOf(A, 1));
    assertEquals(Set.of(A), lineages.currentPathsOf(B, 2));
    assertEquals(Set.of(A), lineages.currentPathsOf(A, 3));
    assertEquals(Set.of(A), lineages.currentPathsOf(A, 4));
  }

  @Test
  void renameAfterTheSnapshotCommitIsIgnored() {
    commit(1);
    commit(2, 1);
    rename(3, 2, A, B);

    final FileLineages lineages = lineagesAt(2, A);

    assertTrue(lineages.isEmpty());
  }

  @Test
  void renameOnUnmergedBranchIsIgnored() {
    commit(1);
    rename(2, 1, A, B); // feature branch
    commit(3, 1); // main

    assertTrue(lineagesAt(3, A).isEmpty());
  }

  @Test
  void renameOnMergedBranchCoversChangesOnBothSidesOfTheMerge() {
    commit(1);
    rename(2, 1, A, B); // feature branch
    commit(3, 2); // feature branch changes B
    commit(4, 1); // main still changes A
    commit(5, 4, 3); // merge into main reports the rename again
    renames.add(new RenameEvent(5, A, B));
    commit(6, 5);

    final FileLineages lineages = lineagesAt(6, B);

    for (final long commitOfA : List.of(1L, 4L)) {
      assertEquals(Set.of(B), lineages.currentPathsOf(A, commitOfA));
    }
    for (final long commitOfB : List.of(2L, 3L, 5L, 6L)) {
      assertEquals(Set.of(B), lineages.currentPathsOf(B, commitOfB));
    }
  }

  @Test
  void commitsWithEqualDatesAreOrderedByAncestry() {
    commits.add(new CommitLink(30, "30", 7, null));
    commits.add(new CommitLink(20, "20", 7, 30L));
    commits.add(new CommitLink(10, "10", 7, 20L));
    renames.add(new RenameEvent(20, A, B));
    renames.add(new RenameEvent(10, B, C));

    final FileLineages lineages = FileLineages.resolve(commits, renames, "10", List.of(C));

    assertEquals(Set.of(C), lineages.currentPathsOf(A, 30));
    assertEquals(Set.of(C), lineages.currentPathsOf(B, 20));
    assertEquals(Set.of(C), lineages.currentPathsOf(C, 10));
  }

  @Test
  void commitActivityIsCountedPerFileInsteadOfPerPath() {
    commit(1);
    commit(2, 1);
    rename(3, 2, A, B);
    commit(4, 3); // adds a new file at A
    final FileLineages lineages = lineagesAt(4, A, B, OTHER);

    final List<ContributorFileActivity> mapped =
        FileLineageActivityMapper.mapCommitActivity(
            List.of(
                new ContributorFileActivity(A, 100, 3, 40),
                new ContributorFileActivity(B, 100, 1, 30),
                new ContributorFileActivity(OTHER, 200, 5, 20)),
            List.of(
                new CommitFileActivity(A, 100, 1, 10),
                new CommitFileActivity(A, 200, 2, 20),
                new CommitFileActivity(B, 100, 3, 30),
                new CommitFileActivity(A, 100, 4, 40)),
            lineages);

    assertEquals(
        Set.of(
            new ContributorFileActivity(OTHER, 200, 5, 20),
            new ContributorFileActivity(B, 100, 2, 30),
            new ContributorFileActivity(B, 200, 1, 20),
            new ContributorFileActivity(A, 100, 1, 40)),
        Set.copyOf(mapped));
    assertEquals(4, mapped.size());
  }

  @Test
  void pullRequestActivityIsAttributedToTheRenamedFile() {
    commit(1);
    rename(2, 1, A, B);
    final FileLineages lineages = lineagesAt(2, B, OTHER);
    final List<PullRequestFileChange> changes =
        List.of(
            new PullRequestFileChange(7, A, 1, 500L),
            new PullRequestFileChange(7, A, 1, 501L),
            new PullRequestFileChange(8, B, 2, 500L),
            new PullRequestFileChange(9, B, 2, null));

    final List<MergedPrStats> prs =
        FileLineageActivityMapper.mapMergedPrStats(
            List.of(new MergedPrStats(7, 1, 2, List.of(A, OTHER))), changes, lineages);
    final Map<String, Long> issueCounts =
        FileLineageActivityMapper.mapIssueCounts(
            Map.of(A, 2L, B, 1L, OTHER, 4L), changes, lineages);

    assertEquals(List.of(new MergedPrStats(7, 1, 2, List.of(OTHER, B))), prs);
    assertEquals(Map.of(B, 2L, OTHER, 4L), issueCounts);
  }

  private FileLineages lineagesAt(final long commitId, final String... snapshotPaths) {
    return FileLineages.resolve(commits, renames, String.valueOf(commitId), List.of(snapshotPaths));
  }

  /** Adds a commit whose date equals its id. */
  private void commit(final long id, final long... parents) {
    if (parents.length == 0) {
      commits.add(new CommitLink(id, String.valueOf(id), id, null));
    }
    for (final long parent : parents) {
      commits.add(new CommitLink(id, String.valueOf(id), id, parent));
    }
  }

  private void rename(final long id, final long parent, final String from, final String to) {
    commit(id, parent);
    renames.add(new RenameEvent(id, from, to));
  }
}
