package net.explorviz.landscape.api.v3.model;

import java.util.List;

/**
 * Request body for fetching static structure data for multiple repositories at once. Each entry
 * selects one or two commits for comparison within that repository.
 *
 * @param repositories Non-empty list of per-repository commit selections
 */
public record EvolutionStructureBatchRequest(List<RepositoryEvolutionSelectionDto> repositories) {}
