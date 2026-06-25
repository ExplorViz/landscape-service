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
    long commitInternalId,
    long rootDirectoryId,
    Map<String, Long> directoryLeafCache) {

  public static CommitFilePersistenceContext create(
      final Session session,
      final DirectoryRepository directoryRepository,
      final String landscapeTokenId,
      final String repoName,
      final long commitInternalId) {
    final long rootDirectoryId =
        directoryRepository.getRepositoryRootDirectoryId(session, landscapeTokenId, repoName);
    final Map<String, Long> directoryLeafCache = new HashMap<>();
    directoryLeafCache.put(repoName, rootDirectoryId);
    return new CommitFilePersistenceContext(
        landscapeTokenId, repoName, commitInternalId, rootDirectoryId, directoryLeafCache);
  }
}
