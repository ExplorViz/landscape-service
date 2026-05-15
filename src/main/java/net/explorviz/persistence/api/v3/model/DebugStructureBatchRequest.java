package net.explorviz.persistence.api.v3.model;

import java.util.List;

/**
 * Request body for fetching static structure data for multiple repositories at once. Each entry
 * selects one debug snapshot within that repository.
 *
 * @param repositories Non-empty list of per-repository debug snapshot selections
 */
public record DebugStructureBatchRequest(List<RepositoryDebugSelectionDto> repositories) {}
