package net.explorviz.landscape.repository;

public record ContributorFileActivity(
    String path,
    long contributorId,
    String githubLogin,
    String gitUsername,
    long commits,
    long lastDate) {}
