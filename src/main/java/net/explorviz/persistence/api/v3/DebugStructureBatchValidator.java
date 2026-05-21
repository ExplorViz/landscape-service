package net.explorviz.persistence.api.v3;

import jakarta.ws.rs.BadRequestException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.explorviz.persistence.api.v3.model.DebugStructureBatchRequest;
import net.explorviz.persistence.api.v3.model.RepositoryDebugSelectionDto;

/** Validates {@link DebugStructureBatchRequest} bodies for {@link StructureResource}. */
final class DebugStructureBatchValidator {

  private DebugStructureBatchValidator() {}

  static List<RepositoryDebugSelectionDto> validatedSelections(
      final DebugStructureBatchRequest request) {

    if (request == null || request.repositories() == null || request.repositories().isEmpty()) {
      throw new BadRequestException("Request must contain a non-empty repositories list");
    }

    final List<RepositoryDebugSelectionDto> repositories = request.repositories();
    final Set<String> seenRepositoryNames = new HashSet<>();
    for (final RepositoryDebugSelectionDto selection : repositories) {
      validateRepositorySelection(selection, seenRepositoryNames);
    }
    return repositories;
  }

  private static void validateRepositorySelection(
      final RepositoryDebugSelectionDto selection, final Set<String> seenRepositoryNames) {

    final String repositoryName = selection.repositoryName();
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new BadRequestException("Each repository must have a non-blank name");
    }
    if (!seenRepositoryNames.add(repositoryName)) {
      throw new BadRequestException("Duplicate repository name: " + repositoryName);
    }
    validateCommitHash(repositoryName, selection.commitHash());
    validateDebugRunId(repositoryName, selection.debugRunId());
    validateDebugSnapshotIds(repositoryName, selection.debugSnapshotIds());
  }

  private static void validateDebugSnapshotIds(
      final String repositoryName, final List<String> debugSnapshotIds) {

    if (debugSnapshotIds == null || debugSnapshotIds.isEmpty() || debugSnapshotIds.size() > 2) {
      throw new BadRequestException(
          "Each repository must have one or two debug snapshot ids (repository: "
              + repositoryName
              + ")");
    }
    debugSnapshotIds.forEach((id) -> validateDebugSnapshotId(repositoryName, id));
  }

  private static void validateCommitHash(final String repositoryName, final String commitHash) {
    requireNonBlankIdOrHash(repositoryName + "/Commit", commitHash);
  }

  private static void validateDebugRunId(final String repositoryName, final String debugRunId) {
    requireNonBlankIdOrHash(repositoryName + "/DebugRun", debugRunId);
  }

  private static void validateDebugSnapshotId(
      final String repositoryName, final String debugSnapshotId) {
    requireNonBlankIdOrHash(repositoryName + "/DebugSnapshot", debugSnapshotId);
  }

  private static void requireNonBlankIdOrHash(final String origin, final String id) {
    if (id == null || id.isBlank()) {
      throw new BadRequestException(origin + ": Id must be non-blank");
    }
  }
}
