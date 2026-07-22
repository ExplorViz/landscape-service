package net.explorviz.landscape.repository;

import net.explorviz.landscape.proto.CommitData;

/** Determines when a child commit must inherit unchanged files from a persisted parent. */
public final class ParentCommitInheritancePolicy {

  private static final String NO_PARENT_ID = "NONE";

  private ParentCommitInheritancePolicy() {}

  public static boolean hasPersistedParentReference(final CommitData commitData) {
    return !commitData.getParentCommitId().isBlank()
        && !NO_PARENT_ID.equals(commitData.getParentCommitId());
  }

  /**
   * Returns {@code true} when missing parent file revisions would leave the child commit
   * incomplete. Bootstrap commits that list every file as added may omit the parent.
   */
  public static boolean requiresPersistedParent(final CommitData commitData) {
    if (!hasPersistedParentReference(commitData)) {
      return false;
    }
    if (!commitData.getUnchangedFilesList().isEmpty()) {
      return false;
    }
    return !commitData.getModifiedFilesList().isEmpty()
        || !commitData.getDeletedFilesList().isEmpty();
  }
}
