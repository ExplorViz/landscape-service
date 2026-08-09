package net.explorviz.landscape.repository;

public record ContributorFileActivity(
    String path, long contributorId, long commits, long lastDate) {}
