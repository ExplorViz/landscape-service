package net.explorviz.persistence.api.v3.model;

/**
 * Selects a debug run and a debug snapshot for static structure retrieval within a single
 * repository.
 *
 * @param repositoryName Name of the repository in the landscape
 * @param debugRunId The selected debug run for this repository
 * @param debugSnapshotId The selected debug snapshot for this debug run
 */
public record RepositoryDebugSelectionDto(
    String repositoryName, String commitHash, String debugRunId, String debugSnapshotId) {}
