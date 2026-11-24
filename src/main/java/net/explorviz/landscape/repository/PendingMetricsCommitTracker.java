package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per repository, the commit that is currently being ingested, i.e. the one whose file
 * data is still arriving and whose metrics therefore have yet to be accumulated.
 *
 * <p>Without it, finding the commits that became ready for {@link CommitMetricsAccumulator} means
 * scanning every commit of the repository several times per ingested commit, so the cost of an
 * analysis grows quadratically with the length of the history. With it, only the tracked commit is
 * looked up by its hash.
 *
 * <p>A repository without an entry is one this instance knows nothing about, e.g. after a restart;
 * callers then have to fall back to the scan. For that reason the entry of an accumulated commit is
 * kept, merely flagged, until the next commit of the repository replaces it.
 *
 * <p>Memory is bounded by one small entry per repository that is being analyzed. An entry is
 * released when a new analysis of the repository announces itself with a state request, when
 * persisting one of its commits fails, and when its landscape is deleted.
 */
@ApplicationScoped
public class PendingMetricsCommitTracker {

  /** The commit a repository is currently ingesting. */
  public record TrackedCommit(String commitHash, boolean accumulated) {}

  private record RepositoryKey(String landscapeTokenId, String repoName) {}

  private final Map<RepositoryKey, TrackedCommit> commitsByRepository = new ConcurrentHashMap<>();

  /** Makes {@code commitHash} the commit the repository is ingesting, replacing the previous. */
  public void track(final String landscapeTokenId, final String repoName, final String commitHash) {
    commitsByRepository.put(
        new RepositoryKey(landscapeTokenId, repoName), new TrackedCommit(commitHash, false));
  }

  public Optional<TrackedCommit> find(final String landscapeTokenId, final String repoName) {
    return Optional.ofNullable(
        commitsByRepository.get(new RepositoryKey(landscapeTokenId, repoName)));
  }

  /** Flags the tracked commit as accumulated, unless the repository moved on to another commit. */
  public void markAccumulated(
      final String landscapeTokenId, final String repoName, final String commitHash) {
    commitsByRepository.computeIfPresent(
        new RepositoryKey(landscapeTokenId, repoName),
        (key, tracked) ->
            tracked.commitHash().equals(commitHash)
                ? new TrackedCommit(commitHash, true)
                : tracked);
  }

  /** Forgets the repository, so that the next check falls back to scanning its commits. */
  public void invalidate(final String landscapeTokenId, final String repoName) {
    commitsByRepository.remove(new RepositoryKey(landscapeTokenId, repoName));
  }

  /** Forgets every repository of the landscape. */
  public void invalidateLandscape(final String landscapeTokenId) {
    commitsByRepository.keySet().removeIf(key -> key.landscapeTokenId().equals(landscapeTokenId));
  }

  public void clear() {
    commitsByRepository.clear();
  }
}
