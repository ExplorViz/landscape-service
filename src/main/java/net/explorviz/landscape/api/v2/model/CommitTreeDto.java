package net.explorviz.landscape.api.v2.model;

import java.util.List;

/**
 * Represents the entire commit-tree of an application within a git repository, where points of
 * branching are explicitly provided for the visualization.
 *
 * @param name Application name
 * @param branches Branches of this application
 */
public record CommitTreeDto(String name, List<BranchDto> branches) {}
