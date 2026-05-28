package net.explorviz.landscape.api.v3.model;

import java.util.List;

/**
 * Represents the entire commit-tree of a git repository, where points of branching are explicitly
 * provided for the visualization.
 *
 * @param name Repository name
 * @param branches Branches of this repository
 */
public record CommitTreeDto(String name, List<BranchDto> branches) {}
