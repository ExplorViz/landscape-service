package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.FileIdentifier;
import org.junit.jupiter.api.Test;

class CommitFileStubPolicyTest {

  @Test
  void commitWithModifiedFilesDoesNotDeferStubCreation() {
    final CommitData commitData =
        CommitData.newBuilder()
            .setAnalysisFileCount(0)
            .addModifiedFiles(
                FileIdentifier.newBuilder().setFilePath("src/A.java").setFileHash("1").build())
            .build();

    assertFalse(CommitFileStubPolicy.defersFileStubCreation(commitData));
  }

  @Test
  void unchangedOnlyChildCommitDoesNotDeferStubCreation() {
    final CommitData commitData =
        CommitData.newBuilder().setAnalysisFileCount(0).setParentCommitId("parent").build();

    assertFalse(CommitFileStubPolicy.defersFileStubCreation(commitData));
  }

  @Test
  void deferredStubCommitDefersFileStubCreation() {
    final CommitData commitData = CommitData.newBuilder().setAnalysisFileCount(3).build();

    assertTrue(CommitFileStubPolicy.defersFileStubCreation(commitData));
  }
}
