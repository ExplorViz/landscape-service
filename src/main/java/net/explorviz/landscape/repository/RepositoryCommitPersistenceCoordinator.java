package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes {@code persistCommit} for a given landscape repository so a child commit cannot
 * observe a partially persisted parent from a concurrent request.
 */
@ApplicationScoped
public class RepositoryCommitPersistenceCoordinator {

  private final ConcurrentMap<String, ReentrantLock> locksByRepoKey = new ConcurrentHashMap<>();

  public <T> T runExclusive(
      final String landscapeTokenId, final String repoName, final Supplier<T> action) {
    final ReentrantLock lock =
        locksByRepoKey.computeIfAbsent(
            repoKey(landscapeTokenId, repoName), ignored -> new ReentrantLock());
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  private static String repoKey(final String landscapeTokenId, final String repoName) {
    return landscapeTokenId + "/" + repoName;
  }
}
