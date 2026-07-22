package net.explorviz.landscape.repository;

/** Aggregated timing data for logging commit persistence. */
public record PersistCommitTimingReport(
    long resolveRepositoryMs,
    long setupCommitMs,
    long createFileContextMs,
    long unlinkDeletedFilesMs,
    long verifyCacheMs,
    long tagsMs,
    long linkParentCommitsMs,
    long finalizeCommitMs,
    long totalMs) {}
