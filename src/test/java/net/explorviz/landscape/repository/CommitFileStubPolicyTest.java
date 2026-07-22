package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.FileIdentifier;
import org.junit.jupiter.api.Test;

class CommitFileStubPolicyTest {

  @Test
  void metadataOnlyCommitHasNoFilesAndZeroAnalysisFileCount() {
    final CommitData commitData =
        CommitData.newBuilder()
            .setAnalysisFileCount(0)
            .setParentCommitId("parent")
            .setMetadataOnly(true)
            .build();

    assertTrue(CommitFileStubPolicy.isMetadataOnlyCommit(commitData));
    assertFalse(CommitFileStubPolicy.defersFileStubCreation(commitData));
  }

  @Test
  void commitWithModifiedFilesIsNotMetadataOnly() {
    final CommitData commitData =
        CommitData.newBuilder()
            .setAnalysisFileCount(0)
            .addModifiedFiles(
                FileIdentifier.newBuilder().setFilePath("src/A.java").setFileHash("1").build())
            .build();

    assertFalse(CommitFileStubPolicy.isMetadataOnlyCommit(commitData));
  }

  @Test
  void unchangedOnlyChildCommitIsNotMetadataOnly() {
    final CommitData commitData =
        CommitData.newBuilder().setAnalysisFileCount(0).setParentCommitId("parent").build();

    assertFalse(CommitFileStubPolicy.isMetadataOnlyCommit(commitData));
  }

  @Test
  void deferredStubCommitIsNotMetadataOnly() {
    final CommitData commitData = CommitData.newBuilder().setAnalysisFileCount(3).build();

    assertFalse(CommitFileStubPolicy.isMetadataOnlyCommit(commitData));
    assertTrue(CommitFileStubPolicy.defersFileStubCreation(commitData));
  }
}
