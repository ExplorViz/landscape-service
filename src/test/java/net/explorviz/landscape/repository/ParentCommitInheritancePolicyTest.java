package net.explorviz.landscape.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.FileIdentifier;
import org.junit.jupiter.api.Test;

class ParentCommitInheritancePolicyTest {

  @Test
  void bootstrapCommitWithOnlyAddedFilesDoesNotRequirePersistedParent() {
    final CommitData commitData =
        CommitData.newBuilder()
            .setParentCommitId("missing-parent")
            .addAddedFiles(
                FileIdentifier.newBuilder().setFilePath("src/A.java").setFileHash("1").build())
            .build();

    assertTrue(ParentCommitInheritancePolicy.hasPersistedParentReference(commitData));
    assertFalse(ParentCommitInheritancePolicy.requiresPersistedParent(commitData));
  }

  @Test
  void incrementalCommitWithModifiedFilesRequiresPersistedParent() {
    final CommitData commitData =
        CommitData.newBuilder()
            .setParentCommitId("parent")
            .addModifiedFiles(
                FileIdentifier.newBuilder().setFilePath("src/A.java").setFileHash("2").build())
            .build();

    assertTrue(ParentCommitInheritancePolicy.requiresPersistedParent(commitData));
  }

  @Test
  void commitWithExplicitUnchangedFilesDoesNotRequirePersistedParent() {
    final CommitData commitData =
        CommitData.newBuilder()
            .setParentCommitId("missing-parent")
            .addUnchangedFiles(
                FileIdentifier.newBuilder().setFilePath("src/A.java").setFileHash("1").build())
            .build();

    assertFalse(ParentCommitInheritancePolicy.requiresPersistedParent(commitData));
  }

  @Test
  void metadataOnlyCommitDoesNotRequirePersistedParent() {
    final CommitData commitData = CommitData.newBuilder().setParentCommitId("parent").build();

    assertTrue(ParentCommitInheritancePolicy.hasPersistedParentReference(commitData));
    assertFalse(ParentCommitInheritancePolicy.requiresPersistedParent(commitData));
  }
}
