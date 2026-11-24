package net.explorviz.landscape.repository;

import java.util.HashMap;
import java.util.Map;
import org.neo4j.ogm.session.Session;

/**
 * Shared state for linking file revisions to a commit within a single {@code persistCommit} call.
 * Resolving the commit node and repository root directory once avoids repeated graph traversals as
 * commit and file counts grow.
 */
public record CommitFilePersistenceContext(
    String landscapeTokenId,
    String repoName,
    String commitHash,
    long commitInternalId,
    long rootDirectoryId,
    Map<String, Long> directoryLeafCache) {

  public static CommitFilePersistenceContext create(
      final Session session,
      final DirectoryRepository directoryRepository,
      final String landscapeTokenId,
      final String repoName,
      final String commitHash,
      final long commitInternalId) {
    return create(
        session,
        directoryRepository,
        landscapeTokenId,
        repoName,
        commitHash,
        commitInternalId,
        new HashMap<>());
  }

  /**
   * Like {@link #create(Session, DirectoryRepository, String, String, String, long)}, but resolves
   * directories with the given cache, which may already know the repository's directories from
   * earlier commits (see {@link DirectoryIdCache}).
   */
  public static CommitFilePersistenceContext create(
      final Session session,
      final DirectoryRepository directoryRepository,
      final String landscapeTokenId,
      final String repoName,
      final String commitHash,
      final long commitInternalId,
      final Map<String, Long> directoryLeafCache) {
    final Long cachedRootDirectoryId = directoryLeafCache.get(repoName);
    final long rootDirectoryId =
        cachedRootDirectoryId != null
            ? cachedRootDirectoryId
            : directoryRepository.getRepositoryRootDirectoryId(session, landscapeTokenId, repoName);
    directoryLeafCache.put(repoName, rootDirectoryId);
    return new CommitFilePersistenceContext(
        landscapeTokenId,
        repoName,
        commitHash,
        commitInternalId,
        rootDirectoryId,
        directoryLeafCache);
  }
}
