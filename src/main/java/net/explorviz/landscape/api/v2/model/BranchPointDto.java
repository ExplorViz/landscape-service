package net.explorviz.landscape.api.v2.model;

/**
 * Represents a git branch as used in the v2-API, where only those commits are considered part of
 * the branch that are not also part of the branch this branch was forked from, i.e. with the
 * abstraction that a commit is only part of exactly one branch (as opposed to multiple like git
 * handles it internally).
 *
 * @param name Origin branch name
 * @param commitId Commit hash from which the new branch emerged
 */
public record BranchPointDto(String name, String commitId) {}
