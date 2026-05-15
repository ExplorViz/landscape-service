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
    validateDebugRunId(repositoryName, selection.debugRunId());
    validateDebugSnapshotId(repositoryName, selection.debugSnapshotId());
  }

  private static void validateDebugRunId(final String repositoryName, final String debugRunId) {
    requireNonBlankId(repositoryName + "/DebugRun", debugRunId);
  }

  private static void validateDebugSnapshotId(
      final String repositoryName, final String debugSnapshotId) {
    requireNonBlankId(repositoryName + "/DebugSnapshot", debugSnapshotId);
  }

  private static void requireNonBlankId(final String origin, final String id) {
    if (id == null || id.isBlank()) {
      throw new BadRequestException(origin + ": Id must be non-blank");
    }
  }
}
