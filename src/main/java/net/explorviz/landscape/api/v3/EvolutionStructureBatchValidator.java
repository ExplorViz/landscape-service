package net.explorviz.landscape.api.v3;

import jakarta.ws.rs.BadRequestException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.EvolutionStructureBatchRequest;
import net.explorviz.landscape.api.v3.model.RepositoryEvolutionSelectionDto;

/** Validates {@link EvolutionStructureBatchRequest} bodies for {@link StructureResource}. */
final class EvolutionStructureBatchValidator {

  private EvolutionStructureBatchValidator() {}

  static List<RepositoryEvolutionSelectionDto> validatedSelections(
      final EvolutionStructureBatchRequest request) {

    if (request == null || request.repositories() == null || request.repositories().isEmpty()) {
      throw new BadRequestException("Request must contain a non-empty repositories list");
    }

    final List<RepositoryEvolutionSelectionDto> repositories = request.repositories();
    final Set<String> seenRepositoryNames = new HashSet<>();
    for (final RepositoryEvolutionSelectionDto selection : repositories) {
      validateRepositorySelection(selection, seenRepositoryNames);
    }
    return repositories;
  }

  private static void validateRepositorySelection(
      final RepositoryEvolutionSelectionDto selection, final Set<String> seenRepositoryNames) {

    final String repositoryName = selection.repositoryName();
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new BadRequestException("Each repository must have a non-blank name");
    }
    if (!seenRepositoryNames.add(repositoryName)) {
      throw new BadRequestException("Duplicate repository name: " + repositoryName);
    }
    validateCommitHashes(repositoryName, selection.commitHashes());
  }

  private static void validateCommitHashes(final String repositoryName, final List<String> hashes) {

    if (hashes == null || hashes.isEmpty() || hashes.size() > 2) {
      throw new BadRequestException(
          "Each repository must have one or two commit hashes (repository: "
              + repositoryName
              + ")");
    }
    hashes.forEach(EvolutionStructureBatchValidator::requireNonBlankHash);
  }

  private static void requireNonBlankHash(final String hash) {
    if (hash == null || hash.isBlank()) {
      throw new BadRequestException("Commit hashes must be non-blank");
    }
  }
}
