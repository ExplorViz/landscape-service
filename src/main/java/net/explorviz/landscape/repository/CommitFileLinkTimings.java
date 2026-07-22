package net.explorviz.landscape.repository;

import java.util.Set;

/** Timing and state produced while linking file revisions to a commit. */
public record CommitFileLinkTimings(
    boolean metadataOnlyCommit,
    boolean deferFileStubs,
    Set<String> deletedPaths,
    long linkAddedFilesMs,
    long linkModifiedFilesMs,
    long linkUnchangedFilesMs,
    long copyUnchangedFromParentMs,
    long unlinkStaleRevisionsMs) {

  public static CommitFileLinkTimings metadataOnly(final Set<String> deletedPaths) {
    return new CommitFileLinkTimings(true, false, deletedPaths, 0, 0, 0, 0, 0);
  }

  public static CommitFileLinkTimings deferred(final Set<String> deletedPaths) {
    return new CommitFileLinkTimings(false, true, deletedPaths, 0, 0, 0, 0, 0);
  }

  public static CommitFileLinkTimings analyzed(
      final Set<String> deletedPaths,
      final long linkAddedFilesMs,
      final long linkModifiedFilesMs,
      final long linkUnchangedFilesMs,
      final long copyUnchangedFromParentMs,
      final long unlinkStaleRevisionsMs) {
    return new CommitFileLinkTimings(
        false,
        false,
        deletedPaths,
        linkAddedFilesMs,
        linkModifiedFilesMs,
        linkUnchangedFilesMs,
        copyUnchangedFromParentMs,
        unlinkStaleRevisionsMs);
  }

  public boolean shouldUnlinkDeletedFiles() {
    return !metadataOnlyCommit && !deferFileStubs;
  }

  public boolean shouldUpdatePendingMetrics() {
    return !deferFileStubs && !metadataOnlyCommit;
  }
}
