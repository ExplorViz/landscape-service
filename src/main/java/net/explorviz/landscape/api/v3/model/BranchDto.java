package net.explorviz.landscape.api.v3.model;

import java.util.List;

/**
 * Represents a git branch, where only those commits are considered part of the branch that are not
 * also part of the branch this branch was forked from, i.e. with the abstraction that a commit is
 * only part of exactly one branch (as opposed to multiple like git handles it internally).
 *
 * @param name Name of the branch
 * @param commits Commits that are considered part of this branch, including accumulated metrics
 * @param branchPoint Commit of different branch where this branch originated from
 */
public record BranchDto(String name, List<CommitNodeDto> commits, BranchPointDto branchPoint) {}
