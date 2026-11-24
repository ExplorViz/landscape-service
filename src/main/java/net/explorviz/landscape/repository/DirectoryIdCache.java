package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory map from a repository's directory paths to their Neo4j node ids, shared by all {@code
 * persistCommit} calls of that repository.
 *
 * <p>Consecutive commits mostly touch directories that already exist. Without this cache, every
 * commit merges the directories of its changed files again, one query per directory depth, although
 * the result is known from earlier commits.
 *
 * <p>Entries are written before the surrounding transaction commits, so the cache of a repository
 * has to be {@linkplain #invalidate invalidated} when that transaction fails. Like the other id
 * caches, it is cleared when a new analysis announces itself with a state request, and whenever
 * landscape data is deleted.
 */
@ApplicationScoped
public class DirectoryIdCache {

  private final Map<String, Map<String, Long>> idsByRepository = new ConcurrentHashMap<>();

  /**
   * Returns the live directory path to node id map of a repository. Keys have the format used by
   * {@link DirectoryRepository#batchMergeDirectoryPaths}.
   */
  public Map<String, Long> forRepository(final String landscapeTokenId, final String repoName) {
    return idsByRepository.computeIfAbsent(
        repoKey(landscapeTokenId, repoName), ignored -> new ConcurrentHashMap<>());
  }

  public void invalidate(final String landscapeTokenId, final String repoName) {
    idsByRepository.remove(repoKey(landscapeTokenId, repoName));
  }

  public void clear() {
    idsByRepository.clear();
  }

  private static String repoKey(final String landscapeTokenId, final String repoName) {
    return landscapeTokenId + "/" + repoName;
  }
}
