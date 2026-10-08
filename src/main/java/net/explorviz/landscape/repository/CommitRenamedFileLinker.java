package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.proto.FileRename;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

/**
 * Connects the file revision a file had before a commit with the one it has after the commit
 * renamed it, via {@code (new:FileRevision)-[:RENAMED_FROM {commitHash, similarity}]->(old)}.
 *
 * <p>Paths are part of a file revision's identity, so without this edge a file's history ends at
 * the path it was renamed from. The edge is keyed by the renaming commit because file revisions are
 * shared between commits: the same pair of revisions can be related by renames on several branches,
 * and a file moved back and forth reuses the same revisions.
 *
 * <p>Renames are additional information on top of the deleted and added files of a {@link
 * CommitData}, which are linked by other components. Linking is therefore best-effort: renames
 * whose revisions cannot be resolved are skipped rather than failing the commit.
 */
@ApplicationScoped
public class CommitRenamedFileLinker {

  public static final String RENAMED_FROM = "RENAMED_FROM";

  private static final String FIND_PARENT_FILE_REVISIONS_AT_PATHS =
      """
      MATCH (parent) WHERE id(parent) = $parentCommitId
      MATCH (parent)-[:CONTAINS]->(f:FileRevision)
      WHERE f.filePath IN $paths
      RETURN f.filePath AS filePath, f.hash AS hash, id(f) AS fileRevId
      """;

  private static final String LINK_RENAMES =
      """
      UNWIND $links AS link
      MATCH (newRev:FileRevision) WHERE id(newRev) = link.newFileRevId
      MATCH (oldRev:FileRevision) WHERE id(oldRev) = link.oldFileRevId
      MERGE (newRev)-[rename:RENAMED_FROM {commitHash: $commitHash}]->(oldRev)
      SET rename.similarity = link.similarity
      RETURN count(rename) AS linkedCount
      """;

  @Inject FileRevisionIdCache fileRevisionIdCache;
  @Inject CommitRepository commitRepository;

  /**
   * Links the renamed files of {@code commitData}. Must run after the added files of the commit
   * were linked, which makes the new file revisions known, and requires the commit to have a
   * persisted parent.
   *
   * @return number of file revision pairs that were linked
   */
  public int linkRenamedFiles(final Session session, final CommitData commitData) {
    final List<FileRename> renames = commitData.getRenamedFilesList();
    if (renames.isEmpty()) {
      return 0;
    }

    final Map<FileRevisionLookupKey, Long> oldFileRevIds =
        resolveOldFileRevisionIds(session, commitData, renames);

    final List<Map<String, Object>> links = new ArrayList<>(renames.size());
    for (final FileRename rename : renames) {
      final Long oldFileRevId = oldFileRevIds.get(lookupKey(commitData, rename.getOldFile()));
      final Long newFileRevId =
          fileRevisionIdCache.get(lookupKey(commitData, rename.getNewFile()).cacheKey());
      if (oldFileRevId == null || newFileRevId == null) {
        Log.debugf(
            "Skipping rename %s -> %s in commit %s of repository '%s': %s revision not found",
            rename.getOldFile().getFilePath(),
            rename.getNewFile().getFilePath(),
            commitData.getCommitId(),
            commitData.getRepositoryName(),
            oldFileRevId == null ? "old" : "new");
        continue;
      }
      links.add(
          Map.of(
              "oldFileRevId",
              oldFileRevId,
              "newFileRevId",
              newFileRevId,
              "similarity",
              rename.getSimilarity()));
    }

    final int linked = writeLinksInBatches(session, commitData.getCommitId(), links);
    Log.debugf(
        "Linked %d of %d renamed files for commit %s in repository '%s'",
        linked, renames.size(), commitData.getCommitId(), commitData.getRepositoryName());
    return linked;
  }

  /**
   * Resolves the file revisions the renamed files had in the parent commit. The in-memory id cache
   * is filled for every file revision this service linked so far and answers almost all lookups;
   * only after a restart does the parent commit have to be consulted.
   */
  private Map<FileRevisionLookupKey, Long> resolveOldFileRevisionIds(
      final Session session, final CommitData commitData, final List<FileRename> renames) {
    final Map<FileRevisionLookupKey, Long> resolved = new HashMap<>(renames.size());
    final Map<String, FileRevisionLookupKey> unresolvedByPath = new HashMap<>();

    for (final FileRename rename : renames) {
      final FileRevisionLookupKey key = lookupKey(commitData, rename.getOldFile());
      final Long fileRevId = fileRevisionIdCache.get(key.cacheKey());
      if (fileRevId != null) {
        resolved.put(key, fileRevId);
      } else {
        unresolvedByPath.put(key.filePath(), key);
      }
    }

    if (!unresolvedByPath.isEmpty()) {
      resolved.putAll(findInParentCommit(session, commitData, unresolvedByPath));
    }
    return resolved;
  }

  /**
   * Looks the given file revisions up in the parent commit. The lookup is anchored at the parent
   * rather than at the file revisions because a path and hash alone do not identify a revision
   * across landscapes that analyze the same repository.
   */
  private Map<FileRevisionLookupKey, Long> findInParentCommit(
      final Session session,
      final CommitData commitData,
      final Map<String, FileRevisionLookupKey> keysByPath) {
    final Optional<Long> parentCommitInternalId =
        commitRepository.findCommitInternalIdInRepository(
            session,
            commitData.getParentCommitId(),
            commitData.getLandscapeToken(),
            commitData.getRepositoryName());
    if (parentCommitInternalId.isEmpty()) {
      return Map.of();
    }

    final Result result =
        session.query(
            FIND_PARENT_FILE_REVISIONS_AT_PATHS,
            Map.of(
                "parentCommitId",
                parentCommitInternalId.get(),
                "paths",
                List.copyOf(keysByPath.keySet())));

    final Map<FileRevisionLookupKey, Long> found = new HashMap<>();
    for (final Map<String, Object> row : result.queryResults()) {
      final FileRevisionLookupKey key = keysByPath.get((String) row.get("filePath"));
      if (key != null && key.hash().equals(row.get("hash"))) {
        final Long fileRevId = (Long) row.get("fileRevId");
        found.put(key, fileRevId);
        fileRevisionIdCache.put(key.cacheKey(), fileRevId);
      }
    }
    return found;
  }

  private int writeLinksInBatches(
      final Session session, final String commitHash, final List<Map<String, Object>> links) {
    int linked = 0;
    for (int offset = 0;
        offset < links.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, links.size());
      final Integer batchLinked =
          session.queryForObject(
              Integer.class,
              LINK_RENAMES,
              Map.of("links", links.subList(offset, end), "commitHash", commitHash));
      linked += batchLinked != null ? batchLinked : 0;
    }
    return linked;
  }

  private static FileRevisionLookupKey lookupKey(
      final CommitData commitData, final FileIdentifier file) {
    return new FileRevisionLookupKey(
        commitData.getLandscapeToken(),
        commitData.getRepositoryName(),
        file.getFilePath(),
        file.getFileHash());
  }
}
