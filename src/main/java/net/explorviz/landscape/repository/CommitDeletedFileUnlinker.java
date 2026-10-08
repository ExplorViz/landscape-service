package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.neo4j.ogm.session.Session;

/**
 * Records file deletions on a commit and ensures deleted files are not part of its {@code CONTAINS}
 * set. Deleted paths are excluded from parent copy; {@code DELETED} edges point at the file
 * revisions that were present on the parent commit.
 */
@ApplicationScoped
public class CommitDeletedFileUnlinker {

  @Inject CommitFileRevisionCache commitFileRevisionCache;
  @Inject CommitRepository commitRepository;

  /**
   * Links {@code DELETED} to the parent commit's file revisions at the given paths, then removes
   * any {@code CONTAINS} links from the child commit to those paths.
   *
   * <p>Paths listed as the old side of a rename in the same commit are omitted from {@code DELETED}
   * history edges; those changes are recorded as renames instead.
   *
   * @return number of {@code CONTAINS} relationships removed
   */
  public int unlinkDeletedFilesFromCommit(
      final Session session,
      final long childCommitInternalId,
      final Set<String> deletedPaths,
      final Set<String> renamedOldPaths,
      final String landscapeTokenId,
      final String repoName,
      final String parentCommitHash) {
    if (deletedPaths.isEmpty()) {
      return 0;
    }

    final Set<String> deletedForHistory = new HashSet<>(deletedPaths);
    if (renamedOldPaths != null) {
      deletedForHistory.removeAll(renamedOldPaths);
    }
    linkDeletedFileRevisions(
        session,
        childCommitInternalId,
        deletedForHistory,
        landscapeTokenId,
        repoName,
        parentCommitHash);

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
              MATCH (child)-[rel:CONTAINS]->(f:FileRevision)
              WHERE f.filePath IN $deletedPaths
              DELETE rel
              RETURN count(rel) AS unlinkedCount
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

  private void linkDeletedFileRevisions(
      final Session session,
      final long childCommitInternalId,
      final Set<String> deletedPaths,
      final String landscapeTokenId,
      final String repoName,
      final String parentCommitHash) {
    if (parentCommitHash == null || parentCommitHash.isBlank()) {
      return;
    }

    final Optional<Long> parentCommitInternalId =
        commitRepository.findCommitInternalIdInRepositoryWithRetry(
            session, parentCommitHash, landscapeTokenId, repoName);
    if (parentCommitInternalId.isEmpty()) {
      Log.warnf(
          "Parent commit %s not found in repository '%s'; skipping DELETED links on commit %d",
          parentCommitHash, repoName, childCommitInternalId);
      return;
    }

    int totalLinked = 0;
    final List<String> deletedPathList = new ArrayList<>(deletedPaths);

    for (int offset = 0;
        offset < deletedPathList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, deletedPathList.size());
      final List<String> batch = deletedPathList.subList(offset, end);

      final Integer linked =
          session.queryForObject(
              Integer.class,
              """
              MATCH (parent) WHERE id(parent) = $parentCommitId
              MATCH (child) WHERE id(child) = $childCommitId
              MATCH (parent)-[:CONTAINS]->(f:FileRevision)
              WHERE f.filePath IN $deletedPaths
              MERGE (child)-[:DELETED]->(f)
              RETURN count(f) AS linkedCount
              """,
              Map.of(
                  "parentCommitId",
                  parentCommitInternalId.get(),
                  "childCommitId",
                  childCommitInternalId,
                  "deletedPaths",
                  batch));

      totalLinked += linked != null ? linked : 0;
    }

    if (totalLinked > 0) {
      Log.infof(
          "Linked %d DELETED relationship(s) on commit %d", totalLinked, childCommitInternalId);
    }
  }
}
