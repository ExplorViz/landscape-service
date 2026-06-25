package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory map from a commit's Neo4j internal ID to its file revisions ({@code filePath →
 * FileRevEntry}), used to avoid re-querying the graph when copying unchanged files from a parent to
 * a child commit.
 *
 * <p>Populated in {@link CommitFileStructureBatchWriter} when file stubs are linked and in {@link
 * UnchangedCommitFileCopier} after a DB-backed copy. Looked up in {@link
 * UnchangedCommitFileCopier#copyFromParent} to skip the graph traversal and redundant property
 * writes when the parent commit's files are already known.
 *
 * <p>Uses an LRU eviction strategy; limits are configurable via {@link
 * CommitFileRevisionCacheProperties}.
 */
@ApplicationScoped
public class CommitFileRevisionCache {

  @Inject CommitFileRevisionCacheProperties cacheProperties;

  private final ReentrantLock lock = new ReentrantLock();

  @SuppressWarnings("serial")
  private final Map<Long, Map<String, FileRevEntry>> commitFiles =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(
            final Map.Entry<Long, Map<String, FileRevEntry>> eldest) {
          return size() > cacheProperties.getMaxCommits();
        }
      };

  public record FileRevEntry(String hash, long fileRevId) {}

  public Optional<Map<String, FileRevEntry>> get(final long commitId) {
    lock.lock();
    try {
      return Optional.ofNullable(commitFiles.get(commitId));
    } finally {
      lock.unlock();
    }
  }

  public int getFileCount(final long commitId) {
    lock.lock();
    try {
      final Map<String, FileRevEntry> files = commitFiles.get(commitId);
      return files != null ? files.size() : 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Merges {@code entries} into the existing file map for {@code commitId} when within configured
   * size limits. Thread-safe.
   */
  public void putAll(final long commitId, final Map<String, FileRevEntry> entries) {
    if (entries.isEmpty()) {
      return;
    }

    lock.lock();
    try {
      final int resultingSize =
          commitFiles.containsKey(commitId)
              ? commitFiles.get(commitId).size() + entries.size()
              : entries.size();
      if (!cacheProperties.shouldCacheFileCount(resultingSize)) {
        Log.debugf(
            "Skipping commit file cache update for commit %d (%d paths exceed limit of %d)",
            commitId, resultingSize, cacheProperties.getMaxFilesPerCommit());
        return;
      }

      commitFiles.compute(
          commitId,
          (id, existing) -> {
            if (existing == null) {
              return new HashMap<>(entries);
            }
            existing.putAll(entries);
            return existing;
          });
    } finally {
      lock.unlock();
    }
  }

  public void clear() {
    lock.lock();
    try {
      commitFiles.clear();
    } finally {
      lock.unlock();
    }
  }

  public void removePaths(final long commitId, final Iterable<String> filePaths) {
    lock.lock();
    try {
      final Map<String, FileRevEntry> existing = commitFiles.get(commitId);
      if (existing != null) {
        filePaths.forEach(existing::remove);
      }
    } finally {
      lock.unlock();
    }
  }
}
