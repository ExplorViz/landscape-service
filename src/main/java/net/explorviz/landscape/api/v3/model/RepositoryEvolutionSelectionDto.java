package net.explorviz.landscape.api.v3.model;

import java.util.List;

/**
 * Selects one or two git commits for static structure retrieval within a single repository. When
 * two hashes are given, the first is the comparison baseline and the second is the target commit
 * (same semantics as {@code GET .../evolution/{repo}/{first}-{second}}).
 *
 * @param repositoryName Name of the repository in the landscape
 * @param commitHashes One commit hash, or two hashes for an evolution comparison
 */
public record RepositoryEvolutionSelectionDto(String repositoryName, List<String> commitHashes) {}
