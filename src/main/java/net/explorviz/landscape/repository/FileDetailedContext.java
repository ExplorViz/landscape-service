package net.explorviz.landscape.repository;

import net.explorviz.landscape.ogm.FileRevision;

public record FileDetailedContext(
    FileRevision fileRevision,
    String remoteUrl,
    String repositoryName,
    String commitHash,
    String fqn) {}
