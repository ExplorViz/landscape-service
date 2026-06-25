package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Bounds in-memory commit file maps used to accelerate parent-to-child file copying. */
@ApplicationScoped
public class CommitFileRevisionCacheProperties {

  /**
   * Maximum number of commits whose file maps are kept in memory (LRU eviction). Increase for
   * repositories with long branch histories; decrease to save heap on multi-tenant hosts.
   */
  @ConfigProperty(
      name = "explorviz.landscape.commit-file-revision-cache-max-commits",
      defaultValue = "512")
  int maxCommits;

  /**
   * Maximum file paths cached per commit. Commits exceeding this limit are still persisted in Neo4j
   * but are not cached, forcing database-backed copy for subsequent commits. Set to {@code 0} for
   * no per-commit limit.
   */
  @ConfigProperty(
      name = "explorviz.landscape.commit-file-revision-cache-max-files-per-commit",
      defaultValue = "200000")
  int maxFilesPerCommit;

  public int getMaxCommits() {
    return maxCommits;
  }

  public int getMaxFilesPerCommit() {
    return maxFilesPerCommit;
  }

  public boolean shouldCacheFileCount(final int fileCount) {
    return maxFilesPerCommit <= 0 || fileCount <= maxFilesPerCommit;
  }
}
