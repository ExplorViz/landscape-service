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
 * <p>A cached commit can additionally be marked "verified complete" ({@link #markVerifiedComplete}
 * / {@link #isVerifiedComplete}), meaning its file map is known to exactly mirror the graph's
 * linked file revisions. {@link UnchangedCommitFileCopier} uses this flag to skip the database
 * round-trips it would otherwise need to confirm the cache is trustworthy, making the common case
 * (copying from a verified parent) an O(1) in-memory operation.
 *
 * <p>Uses an LRU eviction strategy; limits are configurable via {@link
 * CommitFileRevisionCacheProperties}.
 */
@ApplicationScoped
public class CommitFileRevisionCache {

  @Inject CommitFileRevisionCacheProperties cacheProperties;

  private final ReentrantLock lock = new ReentrantLock();

  @SuppressWarnings("serial")
  private final Map<Long, CommitFiles> commitFiles =
      new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, CommitFiles> eldest) {
          return size() > cacheProperties.getMaxCommits();
        }
      };

  public record FileRevEntry(String hash, long fileRevId) {}

  /**
   * Holds a commit's cached file map plus bookkeeping about whether it can be trusted as a
   * complete, exact mirror of the graph without further verification.
   */
  private static final class CommitFiles {
    private final Map<String, FileRevEntry> files;
    private boolean verifiedComplete;
    private boolean truncated;

    private CommitFiles(final Map<String, FileRevEntry> files) {
      this.files = files;
    }
  }

  public Optional<Map<String, FileRevEntry>> get(final long commitId) {
    lock.lock();
    try {
      final CommitFiles entry = commitFiles.get(commitId);
      return entry == null ? Optional.empty() : Optional.of(entry.files);
    } finally {
      lock.unlock();
    }
  }

  public int getFileCount(final long commitId) {
    lock.lock();
    try {
      final CommitFiles entry = commitFiles.get(commitId);
      return entry != null ? entry.files.size() : 0;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns {@code true} if {@code commitId}'s cached file map is known to be a complete, exact
   * mirror of its linked file revisions in the graph. Callers may use this as a fast, O(1)
   * alternative to verifying completeness against the database.
   */
  public boolean isVerifiedComplete(final long commitId) {
    lock.lock();
    try {
      final CommitFiles entry = commitFiles.get(commitId);
      return entry != null && entry.verifiedComplete;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Marks {@code commitId}'s cached file map as verified-complete, so future lookups can trust it
   * without a database round-trip. No-ops if the cache was ever truncated for this commit (i.e. an
   * update was skipped due to size limits), since the cached map may then be missing entries.
   */
  public void markVerifiedComplete(final long commitId) {
    lock.lock();
    try {
      final CommitFiles entry =
          commitFiles.computeIfAbsent(commitId, id -> new CommitFiles(new HashMap<>()));
      if (!entry.truncated) {
        entry.verifiedComplete = true;
      }
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
      final CommitFiles existing = commitFiles.get(commitId);
      final int resultingSize =
          existing != null ? existing.files.size() + entries.size() : entries.size();
      if (!cacheProperties.shouldCacheFileCount(resultingSize)) {
        Log.debugf(
            "Skipping commit file cache update for commit %d (%d paths exceed limit of %d)",
            commitId, resultingSize, cacheProperties.getMaxFilesPerCommit());
        if (existing != null) {
          existing.truncated = true;
          existing.verifiedComplete = false;
        }
        return;
      }

      if (existing == null) {
        commitFiles.put(commitId, new CommitFiles(new HashMap<>(entries)));
      } else {
        existing.files.putAll(entries);
      }
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
      final CommitFiles entry = commitFiles.get(commitId);
      if (entry != null) {
        filePaths.forEach(entry.files::remove);
        entry.verifiedComplete = false;
      }
    } finally {
      lock.unlock();
    }
  }
}
