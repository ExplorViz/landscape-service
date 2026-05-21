package net.explorviz.persistence.api.v3.model;

import java.util.List;

/**
 * Selects a debug run and a debug snapshot for static structure retrieval within a single
 * repository.
 *
 * @param repositoryName Name of the repository in the landscape
 * @param commitHash The commit on which the debug run is based
 * @param debugRunId The selected debug run for this repository
 * @param debugSnapshotIds The selected debug snapshots for this debug run
 */
public record RepositoryDebugSelectionDto(
    String repositoryName, String commitHash, String debugRunId, List<String> debugSnapshotIds) {}
