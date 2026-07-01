package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.ogm.session.Session;

/**
 * Removes stale {@code CONTAINS} links on a commit when an explicitly added or modified file
 * revision shares a directory with an older revision that was copied from the parent commit.
 */
@ApplicationScoped
public class CommitStaleFileRevisionUnlinker {

  /**
   * Resolves candidate stale revisions by {@code filePath} first, then verifies the commit link.
   * Avoids expanding every {@code CONTAINS} edge on large commits (e.g. 90k+ files).
   */
  private static final String UNLINK_STALE_QUERY =
      """
      MATCH (child) WHERE id(child) = $childCommitId
      UNWIND $paths AS filePath
      MATCH (child)-[:%s]->(canonical:FileRevision)
      WHERE canonical.filePath = filePath
      WITH child, canonical, filePath
      MATCH (f:FileRevision)
      WHERE id(f) <> id(canonical)
        AND (
          f.filePath = filePath
          OR (f.filePath IS NULL AND f.name = canonical.name)
        )
      MATCH (child)-[containsRel:CONTAINS]->(f)
      WITH collect(DISTINCT containsRel) AS rels
      FOREACH (rel IN rels | DELETE rel)
      RETURN size(rels) AS unlinkedCount
      """;

  /**
   * Keeps only the {@code ADDED} / {@code MODIFIED} revision at each explicitly changed path.
   *
   * @return number of stale {@code CONTAINS} relationships removed
   */
  public int unlinkStaleRevisionsAtChangedPaths(
      final Session session,
      final long childCommitInternalId,
      final Set<String> modifiedPaths,
      final Set<String> addedPaths) {
    int totalUnlinked = 0;
    totalUnlinked += unlinkStaleAtPaths(session, childCommitInternalId, modifiedPaths, "MODIFIED");
    totalUnlinked += unlinkStaleAtPaths(session, childCommitInternalId, addedPaths, "ADDED");
    return totalUnlinked;
  }

  private int unlinkStaleAtPaths(
      final Session session,
      final long childCommitInternalId,
      final Set<String> paths,
      final String changeRelationshipType) {
    if (paths.isEmpty()) {
      return 0;
    }

    int totalUnlinked = 0;
    final List<String> pathList = new ArrayList<>(paths);
    final String query = unlinkStaleQuery(changeRelationshipType);

    for (int offset = 0;
        offset < pathList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, pathList.size());
      final List<String> batch = pathList.subList(offset, end);

      final Integer unlinked =
          session.queryForObject(
              Integer.class, query, Map.of("childCommitId", childCommitInternalId, "paths", batch));

      totalUnlinked += unlinked != null ? unlinked : 0;
    }

    if (totalUnlinked > 0) {
      Log.infof(
          "Removed %d stale CONTAINS link(s) at %s paths on commit %d",
          totalUnlinked, changeRelationshipType, childCommitInternalId);
    }
    return totalUnlinked;
  }

  private static String unlinkStaleQuery(final String changeRelationshipType) {
    return UNLINK_STALE_QUERY.formatted(changeRelationshipType);
  }
}
