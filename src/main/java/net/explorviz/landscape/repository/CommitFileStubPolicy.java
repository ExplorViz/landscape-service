package net.explorviz.landscape.repository;

import net.explorviz.landscape.proto.CommitData;

/** Detects whether {@code CommitData} defers file-stub creation to the FileData pipeline. */
public final class CommitFileStubPolicy {

  private CommitFileStubPolicy() {}

  /**
   * Returns {@code true} when the analyzer omitted file identifiers and expects stubs to be linked
   * as {@code FileData} arrives.
   */
  public static boolean defersFileStubCreation(final CommitData commitData) {
    return commitData.getAnalysisFileCount() > 0
        && commitData.getAddedFilesList().isEmpty()
        && commitData.getModifiedFilesList().isEmpty()
        && commitData.getUnchangedFilesList().isEmpty();
  }
}
