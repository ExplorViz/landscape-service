package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.ogm.session.Session;

/**
 * Ensures deleted files are not part of a commit's {@code CONTAINS} set. Deleted paths are excluded
 * from parent copy, but must not be linked explicitly either.
 */
@ApplicationScoped
public class CommitDeletedFileUnlinker {

  @Inject CommitFileRevisionCache commitFileRevisionCache;

  /**
   * Removes {@code CONTAINS} links from the commit to any file revision at the given paths.
   *
   * @return number of {@code CONTAINS} relationships removed
   */
  public int unlinkDeletedFilesFromCommit(
      final Session session, final long childCommitInternalId, final Set<String> deletedPaths) {
    if (deletedPaths.isEmpty()) {
      return 0;
    }

    int totalUnlinked = 0;
    final List<String> deletedPathList = new ArrayList<>(deletedPaths);

    for (int offset = 0;
        offset < deletedPathList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, deletedPathList.size());
      final List<String> batch = deletedPathList.subList(offset, end);

      final Integer unlinked =
          session.queryForObject(
              Integer.class,
              """
              MATCH (child) WHERE id(child) = $childCommitId
              UNWIND $deletedPaths AS deletedPath
              MATCH (child)-[rel:CONTAINS]->(f:FileRevision)
              WHERE f.filePath = deletedPath
              WITH collect(rel) AS rels
              FOREACH (rel IN rels | DELETE rel)
              RETURN size(rels) AS unlinkedCount
              """,
              Map.of("childCommitId", childCommitInternalId, "deletedPaths", batch));

      totalUnlinked += unlinked != null ? unlinked : 0;
    }

    commitFileRevisionCache.removePaths(childCommitInternalId, deletedPaths);

    if (totalUnlinked > 0) {
      Log.infof(
          "Removed %d CONTAINS link(s) to deleted file paths on commit %d",
          totalUnlinked, childCommitInternalId);
    }
    return totalUnlinked;
  }
}
