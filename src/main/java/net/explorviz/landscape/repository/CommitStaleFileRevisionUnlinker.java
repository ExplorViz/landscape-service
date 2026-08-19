package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

/**
 * Removes stale {@code CONTAINS} links on a commit when an explicitly added or modified file
 * revision shares a path (or, for legacy nodes without a {@code filePath}, a name) with an older
 * revision that was copied from the parent commit.
 */
@ApplicationScoped
public class CommitStaleFileRevisionUnlinker {

  private record CanonicalEntry(String filePath, long fileRevId, String name) {}

  /**
   * Keeps only the {@code ADDED} / {@code MODIFIED} revision at each explicitly changed path.
   *
   * <p>Finds candidate stale file revisions via the {@code (repoName, filePath)} index — scoped to
   * just the changed paths — rather than by fetching every file revision linked to the child
   * commit, which for large commits (tens of thousands of files) made this step scale with the
   * commit's total size instead of with the (usually much smaller) number of changed paths. The
   * candidate set is then intersected with the child's actual {@code CONTAINS} targets, and only
   * the surviving ids — normally none — are deleted.
   *
   * @return number of stale {@code CONTAINS} relationships removed
   */
  public int unlinkStaleRevisionsAtChangedPaths(
      final Session session,
      final long childCommitInternalId,
      final String repoName,
      final Set<String> modifiedPaths,
      final Set<String> addedPaths) {
    if (modifiedPaths.isEmpty() && addedPaths.isEmpty()) {
      return 0;
    }

    final List<CanonicalEntry> canonicalEntries = new ArrayList<>();
    canonicalEntries.addAll(
        findCanonicalEntries(session, childCommitInternalId, modifiedPaths, "MODIFIED"));
    canonicalEntries.addAll(
        findCanonicalEntries(session, childCommitInternalId, addedPaths, "ADDED"));
    if (canonicalEntries.isEmpty()) {
      return 0;
    }

    final Set<Long> candidateFileRevIds =
        findCandidateDuplicateFileRevIds(session, repoName, canonicalEntries);
    if (candidateFileRevIds.isEmpty()) {
      return 0;
    }

    final int totalUnlinked =
        deleteContainsLinksIfPresent(session, childCommitInternalId, candidateFileRevIds);
    if (totalUnlinked > 0) {
      Log.infof(
          "Removed %d stale CONTAINS link(s) on commit %d", totalUnlinked, childCommitInternalId);
    }
    return totalUnlinked;
  }

  private List<CanonicalEntry> findCanonicalEntries(
      final Session session,
      final long childCommitInternalId,
      final Set<String> paths,
      final String changeRelationshipType) {
    if (paths.isEmpty()) {
      return List.of();
    }

    final List<CanonicalEntry> entries = new ArrayList<>();
    final List<String> pathList = new ArrayList<>(paths);
    for (int offset = 0;
        offset < pathList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, pathList.size());
      final List<String> batch = pathList.subList(offset, end);

      final Result result =
          session.query(
              """
              MATCH (child) WHERE id(child) = $childCommitId
              MATCH (child)-[:%s]->(canonical:FileRevision)
              WHERE canonical.filePath IN $paths
              RETURN
                canonical.filePath AS filePath,
                id(canonical) AS fileRevId,
                canonical.name AS name
              """
                  .formatted(changeRelationshipType),
              Map.of("childCommitId", childCommitInternalId, "paths", batch));

      for (final Map<String, Object> row : result.queryResults()) {
        entries.add(
            new CanonicalEntry(
                (String) row.get("filePath"),
                (Long) row.get("fileRevId"),
                (String) row.get("name")));
      }
    }
    return entries;
  }

  private Set<Long> findCandidateDuplicateFileRevIds(
      final Session session, final String repoName, final List<CanonicalEntry> canonicalEntries) {
    final Set<Long> canonicalIds = new HashSet<>();
    final Set<String> paths = new HashSet<>();
    final Set<String> namesForNullFilePath = new HashSet<>();
    for (final CanonicalEntry entry : canonicalEntries) {
      canonicalIds.add(entry.fileRevId());
      paths.add(entry.filePath());
      if (entry.name() != null) {
        namesForNullFilePath.add(entry.name());
      }
    }

    final Set<Long> candidates = new HashSet<>();
    candidates.addAll(findFileRevIdsAtPaths(session, repoName, paths));
    candidates.addAll(
        findFileRevIdsWithNullFilePathAndName(session, repoName, namesForNullFilePath));
    candidates.removeAll(canonicalIds);
    return candidates;
  }

  /**
   * Uses the {@code (repoName, filePath)} index to seek candidates, not a label scan. A composite
   * index is only used when every indexed property is constrained, so the wider {@code (repoName,
   * filePath, hash)} index cannot serve this hash-less lookup.
   */
  private Set<Long> findFileRevIdsAtPaths(
      final Session session, final String repoName, final Set<String> paths) {
    if (paths.isEmpty()) {
      return Set.of();
    }

    final Set<Long> fileRevIds = new HashSet<>();
    final List<String> pathList = new ArrayList<>(paths);
    for (int offset = 0;
        offset < pathList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, pathList.size());
      final List<String> batch = pathList.subList(offset, end);

      final Result result =
          session.query(
              """
              UNWIND $paths AS filePath
              MATCH (f:FileRevision {repoName: $repoName, filePath: filePath})
              RETURN id(f) AS fileRevId
              """,
              Map.of("repoName", repoName, "paths", batch));
      collectFileRevIds(result, fileRevIds);
    }
    return fileRevIds;
  }

  /** Legacy fallback for nodes created before {@code filePath} tracking existed. */
  private Set<Long> findFileRevIdsWithNullFilePathAndName(
      final Session session, final String repoName, final Set<String> names) {
    if (names.isEmpty()) {
      return Set.of();
    }

    final Set<Long> fileRevIds = new HashSet<>();
    final List<String> nameList = new ArrayList<>(names);
    for (int offset = 0;
        offset < nameList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, nameList.size());
      final List<String> batch = nameList.subList(offset, end);

      final Result result =
          session.query(
              """
              UNWIND $names AS name
              MATCH (f:FileRevision {repoName: $repoName, name: name})
              WHERE f.filePath IS NULL
              RETURN id(f) AS fileRevId
              """,
              Map.of("repoName", repoName, "names", batch));
      collectFileRevIds(result, fileRevIds);
    }
    return fileRevIds;
  }

  private void collectFileRevIds(final Result result, final Set<Long> fileRevIds) {
    for (final Map<String, Object> row : result.queryResults()) {
      fileRevIds.add((Long) row.get("fileRevId"));
    }
  }

  private int deleteContainsLinksIfPresent(
      final Session session,
      final long childCommitInternalId,
      final Set<Long> candidateFileRevIds) {
    final List<Long> idList =
        new ArrayList<>(retainLinkedToChild(session, childCommitInternalId, candidateFileRevIds));
    int totalUnlinked = 0;
    for (int offset = 0;
        offset < idList.size();
        offset += FileRevisionRepository.COMMIT_FILE_BATCH_SIZE) {
      final int end =
          Math.min(offset + FileRevisionRepository.COMMIT_FILE_BATCH_SIZE, idList.size());
      final List<Long> batch = idList.subList(offset, end);

      final Integer unlinked =
          session.queryForObject(
              Integer.class,
              """
              MATCH (child) WHERE id(child) = $childCommitId
              MATCH (child)-[rel:CONTAINS]->(f:FileRevision)
              WHERE id(f) IN $fileRevIds
              DELETE rel
              RETURN count(rel) AS unlinkedCount
              """,
              Map.of("childCommitId", childCommitInternalId, "fileRevIds", batch));
      totalUnlinked += unlinked != null ? unlinked : 0;
    }
    return totalUnlinked;
  }

  /**
   * Narrows the candidates to those the child commit really links, via a single traversal anchored
   * at the child. The candidate set spans every revision ever recorded at the changed paths, so in
   * a long-lived repository it is dominated by revisions belonging to other commits. Testing each
   * candidate against the child instead (a bound-both-ends {@code (child)-[:CONTAINS]->(f)} match)
   * makes Neo4j walk the relationship chain of whichever endpoint has the lower degree, and a file
   * revision reused across thousands of commits still carries thousands of {@code CONTAINS}
   * relationships — so that check scales with the repository's history rather than with this
   * commit.
   */
  private Set<Long> retainLinkedToChild(
      final Session session,
      final long childCommitInternalId,
      final Set<Long> candidateFileRevIds) {
    final Result result =
        session.query(
            """
            MATCH (child) WHERE id(child) = $childCommitId
            MATCH (child)-[:CONTAINS]->(f)
            RETURN id(f) AS fileRevId
            """,
            Map.of("childCommitId", childCommitInternalId));

    final Set<Long> linked = new HashSet<>();
    for (final Map<String, Object> row : result.queryResults()) {
      final Long fileRevId = (Long) row.get("fileRevId");
      if (candidateFileRevIds.contains(fileRevId)) {
        linked.add(fileRevId);
      }
    }
    return linked;
  }
}
