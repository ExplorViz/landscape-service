package net.explorviz.landscape.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.util.CommitFirstParentFilter;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

/** Reads animation commits using the same first-parent selection as the commit tree. */
final class AnimationCommitReader {

  record CommitMeta(String hash, long authorDate) {}

  private AnimationCommitReader() {}

  static List<CommitMeta> fetchOrderedCommits(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo) {
    // Load the complete lineage before applying the date range: the branch head can be
    // outside the requested window, and a merged side branch must stay excluded.
    final Result result =
        session.query(
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit)
            OPTIONAL MATCH (c)-[:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[:HAS_FIRST_PARENT]->(parent:Commit)
            RETURN c.hash AS hash, c.authorDate AS authorDate, c.commitDate AS commitDate,
              b.name AS branch, parent.hash AS parentHash
            """,
            Map.of("tokenId", landscapeToken, "repoName", repositoryName));

    final Map<String, Commit> commitsByHash = new HashMap<>();
    final Map<String, List<Commit>> commitsByBranch = new HashMap<>();
    final Map<String, String> parentHashes = new HashMap<>();
    result.forEach(
        row -> {
          final String hash = (String) row.get("hash");
          final Commit commit = commitsByHash.computeIfAbsent(hash, Commit::new);
          if (row.get("authorDate") instanceof Number date) {
            commit.setAuthorDate(Instant.ofEpochMilli(date.longValue()));
          }
          if (row.get("commitDate") instanceof Number date) {
            commit.setCommitDate(Instant.ofEpochMilli(date.longValue()));
          }
          if (row.get("branch") instanceof String branch) {
            commitsByBranch.computeIfAbsent(branch, ignored -> new ArrayList<>()).add(commit);
          }
          if (row.get("parentHash") instanceof String parentHash) {
            parentHashes.put(hash, parentHash);
          }
        });
    parentHashes.forEach(
        (hash, parentHash) ->
            commitsByHash.get(hash).setFirstParentCommit(commitsByHash.get(parentHash)));

    return commitsByBranch.values().stream()
        .flatMap(commits -> CommitFirstParentFilter.filterToFirstParentOnly(commits).stream())
        .distinct()
        .filter(commit -> commit.getAuthorDate() != null)
        .map(commit -> new CommitMeta(commit.getHash(), commit.getAuthorDate().toEpochMilli()))
        .filter(commit -> commit.authorDate() != 0)
        .filter(commit -> rangeFrom == 0 || commit.authorDate() >= rangeFrom)
        .filter(commit -> rangeTo == 0 || commit.authorDate() <= rangeTo)
        .sorted(Comparator.comparingLong(CommitMeta::authorDate).thenComparing(CommitMeta::hash))
        .toList();
  }
}
