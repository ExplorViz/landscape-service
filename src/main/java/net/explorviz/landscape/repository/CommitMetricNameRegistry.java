package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.neo4j.ogm.session.Session;

/**
 * Tracks which {@code metrics.*} names a repository's {@code FileRevision} nodes carry, so commit
 * metric accumulation can read those properties by name instead of discovering them per file with
 * {@code keys(f)}, which charges one database hit for every property on every file node.
 *
 * <p>Names are recorded as file data is written and mirrored onto the {@code Repository} node, so
 * they survive restarts. The stored list is unioned rather than overwritten, keeping it correct
 * when several service instances ingest the same repository.
 *
 * <p>An empty result means "not known", not "no metrics": repositories ingested before this
 * bookkeeping existed have no stored names, and callers must fall back to key discovery for them.
 * Because {@link FileRevisionBatchResolver} is the only writer of {@code metrics.*} properties on
 * file revisions and records every name it writes, the stored list is complete for any repository
 * this service has ingested.
 */
@ApplicationScoped
public class CommitMetricNameRegistry {

  private static final String LOAD_METRIC_NAMES =
      """
      MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
      RETURN repo.metricNames AS metricNames
      """;

  private static final String STORE_METRIC_NAMES =
      """
      MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
      WITH repo, coalesce(repo.metricNames, []) AS storedNames
      SET repo.metricNames =
        storedNames + [name IN $metricNames WHERE NOT name IN storedNames]
      """;

  private final Map<String, Set<String>> namesByRepository = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> locksByRepository = new ConcurrentHashMap<>();

  /**
   * Reads the repository's known metric names from the graph. Deliberately not served from the
   * in-memory cache: another instance may have recorded a name this one has never written, and
   * silently omitting it would drop that metric from the accumulated sums.
   */
  public Set<String> namesFor(final Session session, final String tokenId, final String repoName) {
    final Set<String> stored = load(session, tokenId, repoName);
    namesByRepository.put(key(tokenId, repoName), stored);
    return Set.copyOf(stored);
  }

  /** Adds {@code metricNames} to the repository's known names, writing only when they change. */
  public void record(
      final Session session,
      final String tokenId,
      final String repoName,
      final Collection<String> metricNames) {
    if (metricNames.isEmpty()) {
      return;
    }

    final String repoKey = key(tokenId, repoName);
    final Set<String> known =
        namesByRepository.computeIfAbsent(repoKey, ignored -> load(session, tokenId, repoName));
    final ReentrantLock lock =
        locksByRepository.computeIfAbsent(repoKey, ignored -> new ReentrantLock());
    final List<String> merged;
    lock.lock();
    try {
      if (!known.addAll(metricNames)) {
        return;
      }
      merged = List.copyOf(known);
    } finally {
      lock.unlock();
    }

    session.query(
        STORE_METRIC_NAMES,
        Map.of("tokenId", tokenId, "repoName", repoName, "metricNames", merged));
  }

  private Set<String> load(final Session session, final String tokenId, final String repoName) {
    final Set<String> names = new LinkedHashSet<>();
    session
        .query(LOAD_METRIC_NAMES, Map.of("tokenId", tokenId, "repoName", repoName))
        .queryResults()
        .forEach(row -> addAll(names, row.get("metricNames")));
    return names;
  }

  private static void addAll(final Set<String> names, final Object storedValue) {
    if (storedValue instanceof String[] array) {
      names.addAll(Arrays.asList(array));
    } else if (storedValue instanceof Object[] array) {
      Arrays.stream(array).forEach(name -> names.add(String.valueOf(name)));
    } else if (storedValue instanceof Collection<?> collection) {
      collection.forEach(name -> names.add(String.valueOf(name)));
    }
  }

  private static String key(final String tokenId, final String repoName) {
    return tokenId + "/" + repoName;
  }
}
