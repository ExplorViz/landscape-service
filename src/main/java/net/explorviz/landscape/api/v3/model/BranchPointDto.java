package net.explorviz.landscape.api.v3.model;

/**
 * Represents a point in a git branch from which another branch has emerged.
 *
 * @param name Name of the branch from which the new branch emerged
 * @param commit Commit hash from which the new branch emerged
 */
public record BranchPointDto(String name, String commit) {}
