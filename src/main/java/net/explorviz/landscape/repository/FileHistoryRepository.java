package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.landscape.FileHistoryDto;
import org.neo4j.ogm.session.Session;

/**
 * Reconstructs the history of a file from the commits that added, modified or deleted its
 * revisions. {@code RENAMED_FROM} edges are followed in both directions, so the history covers the
 * paths the file had before as well as after it was moved, regardless of which of its revisions the
 * history is requested for.
 *
 * <p>Only revisions in the same first-parent lineage as the requested anchor are included:
 * same-path predecessors and successors (including delete then re-add at that path), plus renames.
 * Revisions that merely share a path on another branch are not pulled in.
 */
@ApplicationScoped
public class FileHistoryRepository {

  /** Upper bound on the number of file revisions collected along one lineage. */
  private static final int MAX_REVISIONS = 100;

  private static final String FIND_RENAME_NEIGHBORS =
      """
      MATCH (rev:FileRevision) WHERE id(rev) = $revId
      OPTIONAL MATCH (rev)-[rename:RENAMED_FROM]-(other:FileRevision)
      RETURN
        id(rev) AS fileRevId,
        id(other) AS otherFileRevId,
        id(startNode(rename)) AS renamedFileRevId,
        rename.commitHash AS renameCommitHash,
        coalesce(endNode(rename).filePath, endNode(rename).name) AS previousPath
      """;

  /**
   * Revision that replaced {@code rev} at the same path in the child commit (modification or
   * re-addition after a deletion).
   */
  private static final String FIND_SUCCESSOR_REVISIONS =
      """
      MATCH (prev:FileRevision) WHERE id(prev) = $revId
      MATCH (parent:Commit)-[:CONTAINS]->(prev)
      MATCH (parent)<-[:HAS_FIRST_PARENT]-(child:Commit)
      MATCH (child)-[:ADDED|MODIFIED]->(next:FileRevision)
      WHERE coalesce(next.filePath, next.name) = coalesce(prev.filePath, prev.name)
        AND id(next) <> id(prev)
      RETURN id(next) AS nextId
      UNION
      MATCH (prev:FileRevision) WHERE id(prev) = $revId
      MATCH (del:Commit)-[:DELETED]->(prev)
      MATCH (del)<-[:HAS_FIRST_PARENT]-(child:Commit)
      MATCH (child)-[:ADDED]->(next:FileRevision)
      WHERE coalesce(next.filePath, next.name) = coalesce(prev.filePath, prev.name)
        AND id(next) <> id(prev)
      RETURN id(next) AS nextId
      """;

  /**
   * Revision that {@code rev} replaced at the same path in this commit. The parent may still {@code
   * CONTAINS} that revision, or only retain a {@code DELETED} edge after it was removed in the
   * parent commit itself (delete then re-add).
   */
  private static final String FIND_PREDECESSOR_REVISIONS =
      """
      MATCH (rev:FileRevision) WHERE id(rev) = $revId
      MATCH (child:Commit)-[:ADDED|MODIFIED|DELETED]->(rev)
      MATCH (child)-[:HAS_FIRST_PARENT]->(parent:Commit)
      MATCH (parent)-[:CONTAINS|DELETED]->(prev:FileRevision)
      WHERE coalesce(prev.filePath, prev.name) = coalesce(rev.filePath, rev.name)
        AND id(prev) <> id(rev)
      RETURN DISTINCT id(prev) AS prevId
      """;

  private static final String FIND_CHANGES =
      """
      MATCH (rev:FileRevision) WHERE id(rev) IN $fileRevIds
      MATCH (c:Commit)-[r:ADDED|MODIFIED|DELETED]->(rev)
      OPTIONAL MATCH (author:Contributor)-[:AUTHORED]->(c)
      RETURN
        c.hash AS hash,
        coalesce(c.authorDate, 0) AS date,
        type(r) AS action,
        id(rev) AS fileRevId,
        coalesce(rev.filePath, rev.name) AS path,
        coalesce(author.gitUsername, author.githubLogin, author.email) AS authorName,
        EXISTS { MATCH (c)-[:HAS_MISC_PARENT]->(:Commit) } AS mergeCommit
      ORDER BY date ASC
      """;

  /** Identifies the addition of a revision by the commit that also renamed it. */
  private record RenamedRevision(long fileRevId, String commitHash) {}

  public List<FileHistoryDto> fetchFileHistory(final Session session, final long fileRevisionId) {
    final Set<Long> fileRevIds = new HashSet<>();
    final Map<RenamedRevision, String> previousPaths = new HashMap<>();
    collectRevisionLineage(session, fileRevisionId, fileRevIds, previousPaths);

    if (fileRevIds.isEmpty()) {
      return List.of();
    }

    final List<FileHistoryDto> entries = new ArrayList<>();
    session
        .query(FIND_CHANGES, Map.of("fileRevIds", fileRevIds))
        .queryResults()
        .forEach(row -> entries.add(toHistoryEntry(row, previousPaths)));
    return entries;
  }

  /**
   * Walks the lineage of the anchor revision via renames, same-path predecessors and same-path
   * successors.
   */
  private void collectRevisionLineage(
      final Session session,
      final long startFileRevisionId,
      final Set<Long> fileRevIds,
      final Map<RenamedRevision, String> previousPaths) {
    final Deque<Long> pending = new ArrayDeque<>();
    pending.add(startFileRevisionId);

    while (!pending.isEmpty() && fileRevIds.size() < MAX_REVISIONS) {
      final long revId = pending.poll();
      if (!fileRevIds.add(revId)) {
        continue;
      }

      expandRenameNeighbors(session, revId, pending, previousPaths);
      expandLinkedRevisions(session, FIND_PREDECESSOR_REVISIONS, revId, pending, "prevId");
      expandLinkedRevisions(session, FIND_SUCCESSOR_REVISIONS, revId, pending, "nextId");
    }
  }

  private void expandRenameNeighbors(
      final Session session,
      final long revId,
      final Deque<Long> pending,
      final Map<RenamedRevision, String> previousPaths) {
    for (final Map<String, Object> row :
        session.query(FIND_RENAME_NEIGHBORS, Map.of("revId", revId)).queryResults()) {
      final Long otherFileRevId = (Long) row.get("otherFileRevId");
      if (otherFileRevId != null) {
        pending.add(otherFileRevId);
        previousPaths.put(
            new RenamedRevision(
                (Long) row.get("renamedFileRevId"), (String) row.get("renameCommitHash")),
            (String) row.get("previousPath"));
      }
    }
  }

  private void expandLinkedRevisions(
      final Session session,
      final String query,
      final long revId,
      final Deque<Long> pending,
      final String idField) {
    session
        .query(query, Map.of("revId", revId))
        .queryResults()
        .forEach(row -> pending.add((Long) row.get(idField)));
  }

  /**
   * A revision that was added by a renaming commit is reported as a rename rather than as an
   * addition, since the file already existed under another path.
   */
  private static FileHistoryDto toHistoryEntry(
      final Map<String, Object> row, final Map<RenamedRevision, String> previousPaths) {
    final String hash = (String) row.get("hash");
    final Object date = row.get("date");
    String action = (String) row.get("action");
    String previousPath = null;

    if ("ADDED".equals(action)) {
      previousPath = previousPaths.get(new RenamedRevision((Long) row.get("fileRevId"), hash));
      if (previousPath != null) {
        action = FileHistoryDto.RENAMED;
      }
    }

    return new FileHistoryDto(
        hash,
        date instanceof Number n ? n.longValue() : 0L,
        action,
        (String) row.get("path"),
        previousPath,
        (String) row.get("authorName"),
        Boolean.TRUE.equals(row.get("mergeCommit")));
  }
}
