package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

/**
 * Copies unchanged file revisions from a parent commit to a child commit, using an in-memory cache
 * when available to avoid redundant graph traversals.
 */
@ApplicationScoped
public class UnchangedCommitFileCopier {

  @Inject FileRevisionIdCache fileRevisionIdCache;
  @Inject CommitFileRevisionCache commitFileRevisionCache;
  @Inject CommitRepository commitRepository;

  public record CopyUnchangedFilesFromParentRequest(
      String landscapeTokenId,
      String repoName,
      long parentCommitInternalId,
      long childCommitInternalId,
      Set<String> addedPaths,
      Set<String> modifiedPaths,
      Set<String> deletedPaths) {}

  /**
   * Links every parent file revision not listed as added, modified, or deleted to the child commit.
   *
   * @return number of unchanged file revisions linked to the child commit
   */
  public int copyFromParent(
      final Session session, final CopyUnchangedFilesFromParentRequest request) {
    final long copyStart = System.nanoTime();
    final int parentLinkedCount =
        commitRepository.countLinkedFileRevisions(session, request.parentCommitInternalId());
    if (parentLinkedCount == 0) {
      Log.warnf(
          "Parent commit %d has no linked file revisions; skipping unchanged-file copy to child %d",
          request.parentCommitInternalId(), request.childCommitInternalId());
      return 0;
    }

    final Optional<Map<String, CommitFileRevisionCache.FileRevEntry>> cachedParent =
        commitFileRevisionCache.get(request.parentCommitInternalId());
    final int copied =
        cachedParent.isPresent()
                && isCacheCompleteForParent(
                    session, request.parentCommitInternalId(), cachedParent.get())
            ? copyUnchangedFilesFromCache(session, request, cachedParent.get())
            : copyUnchangedFilesFromDb(session, request);

    validateCopyResult(request, parentLinkedCount, copied);
    Log.infof(
        "copyUnchangedFromParent(parent=%d, child=%d, repo='%s'): copied %d files in %dms",
        request.parentCommitInternalId(),
        request.childCommitInternalId(),
        request.repoName(),
        copied,
        elapsedMillis(copyStart));
    return copied;
  }

  /**
   * Copies unchanged files and fails if the child commit does not receive the expected minimum
   * number of file links.
   */
  public void copyFromParentOrThrow(
      final Session session, final CopyUnchangedFilesFromParentRequest request) {
    copyFromParent(session, request);
  }

  private boolean isCacheCompleteForParent(
      final Session session,
      final long parentCommitInternalId,
      final Map<String, CommitFileRevisionCache.FileRevEntry> cachedParent) {
    final int linkedInDb =
        commitRepository.countLinkedFileRevisions(session, parentCommitInternalId);
    if (cachedParent.size() < linkedInDb) {
      Log.debugf(
          "Parent commit %d cache has %d paths but %d files are linked in the graph; "
              + "using database-backed copy",
          parentCommitInternalId, cachedParent.size(), linkedInDb);
      return false;
    }
    return true;
  }

  private void validateCopyResult(
      final CopyUnchangedFilesFromParentRequest request,
      final int parentLinkedCount,
      final int copied) {
    final int excludedCount = excludedPaths(request).size();
    final int expectedMinimum = Math.max(0, parentLinkedCount - excludedCount);
    if (copied < expectedMinimum) {
      throw new IncompleteCommitFileCopyException(
          String.format(
              "Copied %d unchanged files from parent commit %d to child %d for repository '%s', "
                  + "but expected at least %d (parent had %d linked files, %d excluded paths)",
              copied,
              request.parentCommitInternalId(),
              request.childCommitInternalId(),
              request.repoName(),
              expectedMinimum,
              parentLinkedCount,
              excludedCount));
    } else if (copied > 0) {
      Log.debugf(
          "Copied %d unchanged file revisions from parent commit %d to child commit %d "
              + "for repository '%s'",
          copied,
          request.parentCommitInternalId(),
          request.childCommitInternalId(),
          request.repoName());
    } else if (expectedMinimum > 0) {
      throw new IncompleteCommitFileCopyException(
          String.format(
              "No unchanged files copied from parent commit %d to child commit %d for "
                  + "repository '%s' (expected at least %d)",
              request.parentCommitInternalId(),
              request.childCommitInternalId(),
              request.repoName(),
              expectedMinimum));
    }
  }

  private Set<String> excludedPaths(final CopyUnchangedFilesFromParentRequest request) {
    final Set<String> excluded = new HashSet<>();
    excluded.addAll(request.addedPaths());
    excluded.addAll(request.modifiedPaths());
    excluded.addAll(request.deletedPaths());
    return excluded;
  }

  private int copyUnchangedFilesFromCache(
      final Session session,
      final CopyUnchangedFilesFromParentRequest request,
      final Map<String, CommitFileRevisionCache.FileRevEntry> parentFiles) {
    final Set<String> excluded = excludedPaths(request);
    final Map<String, CommitFileRevisionCache.FileRevEntry> unchangedFiles =
        new HashMap<>(parentFiles.size());
    final List<Long> unchangedFileRevIds = new ArrayList<>();

    for (final Map.Entry<String, CommitFileRevisionCache.FileRevEntry> entry :
        parentFiles.entrySet()) {
      final String filePath = entry.getKey();
      if (!excluded.contains(filePath)) {
        unchangedFiles.put(filePath, entry.getValue());
        unchangedFileRevIds.add(entry.getValue().fileRevId());
      }
    }

    linkFileRevisionsToChildInBatches(
        session, request.childCommitInternalId(), unchangedFileRevIds);
    populateCachesFromMemory(request, unchangedFiles);
    return unchangedFiles.size();
  }

  private int copyUnchangedFilesFromDb(
      final Session session, final CopyUnchangedFilesFromParentRequest request) {
    final Set<String> excluded = excludedPaths(request);
    final Map<String, CommitFileRevisionCache.FileRevEntry> childEntries = new HashMap<>();
    int totalCopied = 0;
    int offset = 0;

    while (true) {
      final Map<String, Object> params = new HashMap<>();
      params.put("repoName", request.repoName());
      params.put("parentCommitId", request.parentCommitInternalId());
      params.put("childCommitId", request.childCommitInternalId());
      params.put("excludedPaths", excluded);
      params.put("tokenId", request.landscapeTokenId());
      params.put("offset", offset);
      params.put("batchSize", FileRevisionRepository.COMMIT_FILE_BATCH_SIZE);

      final Result result =
          session.query(
              """
              MATCH (parent) WHERE id(parent) = $parentCommitId
              MATCH (child) WHERE id(child) = $childCommitId
              MATCH (parent)-[:CONTAINS]->(f:FileRevision)
              WHERE f.filePath IS NULL OR NOT f.filePath IN $excludedPaths
              WITH f, child, coalesce(f.filePath, f.name) AS path
              ORDER BY id(f)
              SKIP $offset LIMIT $batchSize
              MERGE (child)-[:CONTAINS]->(f)
              WITH f, child, path, $repoName AS repoName, $tokenId AS tokenId
              SET f.repoName = repoName,
                f.filePath = path,
                f.lookupKey = coalesce(
                  f.lookupKey, tokenId + '/' + repoName + '/' + path + '/' + f.hash)
              RETURN path AS filePath, f.hash AS hash, id(f) AS fileRevId
              """,
              params);

      int batchCopied = 0;
      for (final Map<String, Object> row : result.queryResults()) {
        batchCopied++;
        final String filePath = (String) row.get("filePath");
        final String hash = (String) row.get("hash");
        final Long fileRevId = (Long) row.get("fileRevId");
        fileRevisionIdCache.put(
            new FileRevisionLookupKey(
                    request.landscapeTokenId(), request.repoName(), filePath, hash)
                .cacheKey(),
            fileRevId);
        childEntries.put(filePath, new CommitFileRevisionCache.FileRevEntry(hash, fileRevId));
      }

      if (batchCopied == 0) {
        break;
      }

      totalCopied += batchCopied;
      offset += batchCopied;
      if (batchCopied < FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
        break;
      }
    }

    if (!childEntries.isEmpty()) {
      commitFileRevisionCache.putAll(request.childCommitInternalId(), childEntries);
    }
    return totalCopied;
  }

  private void linkFileRevisionsToChildInBatches(
      final Session session, final long childCommitInternalId, final List<Long> fileRevIds) {
    for (int offset = 0;
        offset < fileRevIds.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, fileRevIds.size());
      final List<Long> batch = fileRevIds.subList(offset, end);
      if (batch.isEmpty()) {
        continue;
      }
      session.query(
          """
          MATCH (child) WHERE id(child) = $childCommitId
          WITH child
          UNWIND $fileRevIds AS fId
          MATCH (f:FileRevision) WHERE id(f) = fId
          MERGE (child)-[:CONTAINS]->(f)
          """,
          Map.of("childCommitId", childCommitInternalId, "fileRevIds", batch));
    }
  }

  private void populateCachesFromMemory(
      final CopyUnchangedFilesFromParentRequest request,
      final Map<String, CommitFileRevisionCache.FileRevEntry> unchangedFiles) {
    unchangedFiles.forEach(
        (filePath, entry) ->
            fileRevisionIdCache.put(
                new FileRevisionLookupKey(
                        request.landscapeTokenId(), request.repoName(), filePath, entry.hash())
                    .cacheKey(),
                entry.fileRevId()));
    commitFileRevisionCache.putAll(request.childCommitInternalId(), unchangedFiles);
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
