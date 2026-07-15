package net.explorviz.landscape.repository;

public record FileSnapshot(long fileRevisionId, String path, double loc) {}
