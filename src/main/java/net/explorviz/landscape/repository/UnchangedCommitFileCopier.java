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
import java.util.stream.Collectors;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

/**
 * Copies unchanged file revisions from a parent commit to a child commit, using an in-memory cache
 * when available to avoid redundant graph traversals.
 */
@ApplicationScoped
@SuppressWarnings("PMD.TooManyMethods")
public class UnchangedCommitFileCopier {

  private static final String COUNT_PARENT_FILES_AT_EXCLUDED_PATHS =
      """
      MATCH (parent) WHERE id(parent) = $parentCommitId
      MATCH (parent)-[:CONTAINS]->(f:FileRevision)
      WHERE f.filePath IN $excludedPaths
      RETURN count(f) AS excludedCount
      """;
  @Inject FileRevisionIdCache fileRevisionIdCache;
  @Inject CommitFileRevisionCache commitFileRevisionCache;
  @Inject CommitRepository commitRepository;

  public record CopyUnchangedFilesFromParentRequest(
      String landscapeTokenId,
      String repoName,
      String parentCommitHash,
      long parentCommitInternalId,
      long childCommitInternalId,
      Set<String> addedPaths,
      Set<String> modifiedPaths,
      Set<String> deletedPaths,
      boolean requirePersistedParent) {}

  /**
   * Links every parent file revision not listed as added, modified, or deleted to the child commit.
   *
   * <p>When the parent's cache entry is already {@linkplain
   * CommitFileRevisionCache#isVerifiedComplete verified complete}, this runs with zero database
   * round-trips beyond the batched relationship writes themselves: both the parent's file count and
   * the excluded-path overlap are derived from the in-memory map instead of querying the graph.
   * Otherwise it falls back to a database-verified copy, which self-heals the verified flag for
   * subsequent copies from the same parent.
   *
   * @return number of unchanged file revisions linked to the child commit
   */
  public int copyFromParent(
      final Session session, final CopyUnchangedFilesFromParentRequest request) {
    final long copyStart = System.nanoTime();
    final long parentCommitInternalId = request.parentCommitInternalId();

    final Optional<Map<String, CommitFileRevisionCache.FileRevEntry>> cachedParent =
        commitFileRevisionCache.get(parentCommitInternalId);
    final int copied =
        cachedParent.isPresent()
                && commitFileRevisionCache.isVerifiedComplete(parentCommitInternalId)
            ? copyFromVerifiedCache(session, request, cachedParent.get())
            : copyFromParentWithDatabaseVerification(session, request, cachedParent);

    Log.infof(
        "copyUnchangedFromParent(parent=%d, child=%d, repo='%s'): copied %d files in %dms",
        parentCommitInternalId,
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

  /**
   * Verifies that {@code commitInternalId}'s in-memory file cache exactly matches the graph's
   * linked file revisions and, if so, marks it verified-complete so it becomes an O(1) copy source
   * (no per-copy database verification) once it is used as a parent commit. Intended to be called
   * once, right after a commit's own file-linking steps (added/modified/unchanged/copy/unlink) have
   * all completed.
   */
  public void verifyAndMarkComplete(final Session session, final long commitInternalId) {
    final int cachedCount = commitFileRevisionCache.getFileCount(commitInternalId);
    final int linkedCount = commitRepository.countLinkedFileRevisions(session, commitInternalId);
    if (cachedCount == linkedCount) {
      commitFileRevisionCache.markVerifiedComplete(commitInternalId);
    } else {
      Log.debugf(
          "Commit %d cache has %d paths but %d files are linked in the graph; "
              + "will re-verify against the database when used as a parent commit",
          commitInternalId, cachedCount, linkedCount);
    }
  }

  private int copyFromVerifiedCache(
      final Session session,
      final CopyUnchangedFilesFromParentRequest request,
      final Map<String, CommitFileRevisionCache.FileRevEntry> parentFiles) {
    final int copied = copyUnchangedFilesFromCache(session, request, parentFiles);
    final int excludedOnParent = countExcludedPathsInMap(excludedPaths(request), parentFiles);
    validateCopyResult(request, parentFiles.size(), excludedOnParent, copied);
    return copied;
  }

  private int copyFromParentWithDatabaseVerification(
      final Session session,
      final CopyUnchangedFilesFromParentRequest request,
      final Optional<Map<String, CommitFileRevisionCache.FileRevEntry>> cachedParent) {
    final long parentCommitInternalId = request.parentCommitInternalId();
    final int parentLinkedCount =
        commitRepository.countLinkedFileRevisions(session, parentCommitInternalId);
    if (parentLinkedCount == 0) {
      if (request.requirePersistedParent()) {
        throw new ParentCommitNotReadyException(
            String.format(
                "Parent commit %s (id=%d) has no linked file revisions; cannot inherit unchanged"
                    + " files for child %d in repository '%s'",
                request.parentCommitHash(),
                parentCommitInternalId,
                request.childCommitInternalId(),
                request.repoName()));
      }
      Log.debugf(
          "Parent commit %d has no linked file revisions; skipping unchanged-file copy to child %d",
          parentCommitInternalId, request.childCommitInternalId());
      return 0;
    }

    final boolean cacheComplete =
        cachedParent.isPresent() && cachedParent.get().size() >= parentLinkedCount;
    final int copied;
    if (cacheComplete) {
      commitFileRevisionCache.markVerifiedComplete(parentCommitInternalId);
      copied = copyUnchangedFilesFromCache(session, request, cachedParent.get());
    } else {
      if (cachedParent.isPresent()) {
        Log.debugf(
            "Parent commit %d cache has %d paths but %d files are linked in the graph; "
                + "using database-backed copy",
            parentCommitInternalId, cachedParent.get().size(), parentLinkedCount);
      }
      copied = copyUnchangedFilesFromDb(session, request);
    }

    final int excludedOnParent =
        countParentFilesAtExcludedPaths(session, parentCommitInternalId, excludedPaths(request));
    validateCopyResult(request, parentLinkedCount, excludedOnParent, copied);
    return copied;
  }

  private int countExcludedPathsInMap(
      final Set<String> excludedPaths,
      final Map<String, CommitFileRevisionCache.FileRevEntry> parentFiles) {
    if (excludedPaths.isEmpty()) {
      return 0;
    }
    int count = 0;
    for (final String excludedPath : excludedPaths) {
      if (parentFiles.containsKey(excludedPath)) {
        count++;
      }
    }
    return count;
  }

  private void validateCopyResult(
      final CopyUnchangedFilesFromParentRequest request,
      final int parentLinkedCount,
      final int excludedOnParent,
      final int copied) {
    final int expectedMinimum = Math.max(0, parentLinkedCount - excludedOnParent);
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
              excludedOnParent));
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
    final Set<Long> alreadyLinkedFileRevIds =
        findExistingContainsTargetIds(session, request.childCommitInternalId());
    int totalCopied = 0;
    int offset = 0;

    while (true) {
      final Map<String, Object> params = new HashMap<>();
      params.put("repoName", request.repoName());
      params.put("parentCommitId", request.parentCommitInternalId());
      params.put("childCommitId", request.childCommitInternalId());
      params.put("excludedPaths", excluded);
      params.put("alreadyLinkedFileRevIds", alreadyLinkedFileRevIds);
      params.put("tokenId", request.landscapeTokenId());
      params.put("lookupKeyPrefix", request.landscapeTokenId() + "/" + request.repoName() + "/");
      params.put("offset", offset);
      params.put("batchSize", FileRevisionRepository.COMMIT_FILE_BATCH_SIZE);

      final Result result =
          session.query(
              """
              MATCH (parent) WHERE id(parent) = $parentCommitId
              MATCH (child) WHERE id(child) = $childCommitId
              MATCH (parent)-[:CONTAINS]->(f:FileRevision)
              WITH f, child, $lookupKeyPrefix AS lookupPrefix,
                coalesce(
                  f.filePath,
                  CASE
                    WHEN f.lookupKey IS NOT NULL
                      AND f.lookupKey STARTS WITH lookupPrefix
                      AND f.hash IS NOT NULL
                    THEN substring(
                      f.lookupKey,
                      size(lookupPrefix),
                      size(f.lookupKey) - size(lookupPrefix) - size(f.hash) - 1)
                    ELSE null
                  END,
                  f.name
                ) AS resolvedPath
              WHERE resolvedPath IS NULL OR NOT resolvedPath IN $excludedPaths
              WITH f, child, coalesce(f.filePath, resolvedPath) AS path
              ORDER BY id(f)
              SKIP $offset LIMIT $batchSize
              FOREACH (
                _ IN CASE WHEN id(f) IN $alreadyLinkedFileRevIds THEN [] ELSE [1] END |
                CREATE (child)-[:CONTAINS]->(f)
              )
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

  /**
   * Links {@code fileRevIds} to the child commit in batches, creating only relationships that don't
   * already exist.
   *
   * <p>Deliberately avoids {@code MERGE (child)-[:CONTAINS]->(f)}: with both endpoints already
   * bound, Neo4j must scan one side's relationship chain to check whether the link already exists,
   * and {@code FileRevision} nodes for files that stay unchanged across many commits (very common
   * in long-lived repositories) can accumulate an enormous {@code CONTAINS} in-degree over time.
   * That made each existence check — and therefore this whole copy — scale with the repository's
   * total history rather than with this commit's own file count. Instead, existing links are looked
   * up once via a traversal anchored at the child commit (whose own degree is always small and
   * bounded by its file count) and only the missing links are created.
   */
  private void linkFileRevisionsToChildInBatches(
      final Session session, final long childCommitInternalId, final List<Long> fileRevIds) {
    if (fileRevIds.isEmpty()) {
      return;
    }

    final Set<Long> alreadyLinkedFileRevIds =
        findExistingContainsTargetIds(session, childCommitInternalId);

    for (int offset = 0;
        offset < fileRevIds.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, fileRevIds.size());
      final List<Long> batch =
          fileRevIds.subList(offset, end).stream()
              .filter(fileRevId -> !alreadyLinkedFileRevIds.contains(fileRevId))
              .collect(Collectors.toList());
      if (batch.isEmpty()) {
        continue;
      }
      session.query(
          """
          MATCH (child) WHERE id(child) = $childCommitId
          WITH child
          UNWIND $fileRevIds AS fId
          MATCH (f:FileRevision) WHERE id(f) = fId
          CREATE (child)-[:CONTAINS]->(f)
          """,
          Map.of("childCommitId", childCommitInternalId, "fileRevIds", batch));
    }
  }

  /**
   * Returns the ids of file revisions already linked to {@code commitInternalId} via {@code
   * CONTAINS}. Anchored at the commit rather than at individual file revisions so the traversal
   * cost is bounded by the commit's own file count, not by how many commits across the repository's
   * history happen to link to the same (possibly very high-degree) file revision node.
   */
  private Set<Long> findExistingContainsTargetIds(
      final Session session, final long commitInternalId) {
    final Result result =
        session.query(
            """
            MATCH (commit) WHERE id(commit) = $commitId
            MATCH (commit)-[:CONTAINS]->(f)
            RETURN id(f) AS fileRevId
            """,
            Map.of("commitId", commitInternalId));
    final Set<Long> fileRevIds = new HashSet<>();
    for (final Map<String, Object> row : result.queryResults()) {
      fileRevIds.add((Long) row.get("fileRevId"));
    }
    return fileRevIds;
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

  private int countParentFilesAtExcludedPaths(
      final Session session, final long parentCommitInternalId, final Set<String> excludedPaths) {
    if (excludedPaths.isEmpty()) {
      return 0;
    }
    final Integer count =
        session.queryForObject(
            Integer.class,
            COUNT_PARENT_FILES_AT_EXCLUDED_PATHS,
            Map.of(
                "parentCommitId",
                parentCommitInternalId,
                "excludedPaths",
                new ArrayList<>(excludedPaths)));
    return count != null ? count : 0;
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
