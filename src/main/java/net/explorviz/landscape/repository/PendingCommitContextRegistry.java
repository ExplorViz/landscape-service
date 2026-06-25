package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks commits whose file stubs are created on demand when {@code FileData} arrives, instead of
 * being bulk-linked during {@code persistCommit}.
 */
@ApplicationScoped
public class PendingCommitContextRegistry {

  private final Map<String, PendingCommit> pendingByRepoKey = new ConcurrentHashMap<>();

  public record PendingCommit(
      String landscapeTokenId,
      String repoName,
      String commitHash,
      long commitInternalId,
      int analysisFileCount) {}

  public void register(final PendingCommit pendingCommit) {
    pendingByRepoKey.put(
        repoKey(pendingCommit.landscapeTokenId(), pendingCommit.repoName()), pendingCommit);
  }

  public void unregister(final String landscapeTokenId, final String repoName) {
    pendingByRepoKey.remove(repoKey(landscapeTokenId, repoName));
  }

  public Optional<PendingCommit> find(final String landscapeTokenId, final String repoName) {
    return Optional.ofNullable(pendingByRepoKey.get(repoKey(landscapeTokenId, repoName)));
  }

  private static String repoKey(final String landscapeTokenId, final String repoName) {
    return landscapeTokenId + "/" + repoName;
  }
}
