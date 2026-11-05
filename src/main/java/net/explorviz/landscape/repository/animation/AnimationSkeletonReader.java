package net.explorviz.landscape.repository;

import static net.explorviz.landscape.repository.StructureMapper.buildFlatLandscape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;
import net.explorviz.landscape.api.v3.model.landscape.AnimationSkeletonDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.repository.AnimationCommitReader.CommitMeta;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

/** Builds the animation hierarchy once per file path, rather than once per historical revision. */
final class AnimationSkeletonReader {

  private AnimationSkeletonReader() {}

  static AnimationSkeletonDto fetchSkeleton(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final List<CommitMeta> commits) {
    if (commits.isEmpty()) {
      return new AnimationSkeletonDto(
          FlatLandscapeDto.newEmptyLandscape(landscapeToken), Map.of(), List.of(), List.of());
    }

    final Map<String, Integer> ordinals = new HashMap<>();
    for (int i = 0; i < commits.size(); i++) {
      ordinals.put(commits.get(i).hash(), i);
    }
    // Aggregate commit/file edges once, before expanding any application or directory paths.
    // Map lookup avoids testing every commit against a potentially large list of hashes. The
    // ordinal is looked up once per commit, and the label of the file is checked only after the
    // aggregation: the edges of all commits to their files are by far the largest part of the
    // graph that is read, so no per-edge work other than the aggregation should be done for them.
    final Result files =
        session.query(
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})-[:CONTAINS]->(c:Commit)
            WITH c, $ordinals[c.hash] AS ordinal
            WHERE ordinal IS NOT NULL
            MATCH (c)-[:CONTAINS]->(f)
            WITH f, min(ordinal) AS firstOrdinal, max(ordinal) AS lastOrdinal
            WHERE f:FileRevision
            ORDER BY lastOrdinal DESC, id(f) DESC
            RETURN f.filePath AS fqn, min(firstOrdinal) AS firstOrdinal,
              head(collect(id(f))) AS revisionId
            """,
            Map.of("tokenId", landscapeToken, "repoName", repositoryName, "ordinals", ordinals));

    final Map<String, Integer> firstOrdinals = new HashMap<>();
    final List<Long> revisionIds = new ArrayList<>();
    files.forEach(
        row -> {
          revisionIds.add(((Number) row.get("revisionId")).longValue());
          if (row.get("fqn") instanceof String fqn) {
            firstOrdinals.put(fqn, ((Number) row.get("firstOrdinal")).intValue());
          }
        });

    return new AnimationSkeletonDto(
        buildSkeleton(session, landscapeToken, revisionIds),
        firstOrdinals,
        commits.stream().map(CommitMeta::hash).toList(),
        commits.stream().map(CommitMeta::authorDate).toList());
  }

  private static FlatLandscapeDto buildSkeleton(
      final Session session, final String landscapeToken, final List<Long> revisionIds) {
    if (revisionIds.isEmpty()) {
      return FlatLandscapeDto.newEmptyLandscape(landscapeToken);
    }
    final Result result =
        session.query(
            """
            CYPHER 25
            MATCH (revision:FileRevision) WHERE id(revision) IN $revisionIds
            WITH collect(revision) AS revisions
            // Walk the directory tree down from the application roots, avoiding the file's
            // incoming commit edges, and keep the files of interest (hash lookup).
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(a:Application)-[:HAS_ROOT]->(appRoot:Directory)
            MATCH chain = (appRoot) ( ()-[:CONTAINS]->(:Directory) )* (parent:Directory)
              -[:CONTAINS]->(rep:FileRevision)
            WHERE rep IN revisions
            // Omit the application's root directory from both the hierarchy and file paths.
            WITH a, [a] + nodes(chain)[1..] AS pathNodes
            UNWIND range(0, size(pathNodes) - 1) AS position
            WITH a, pathNodes, position, pathNodes[position] AS n
            RETURN DISTINCT
              id(n) AS id,
              labels(n) AS labels,
              {k IN keys(n)
               WHERE k IN ['language', 'hash'] OR k STARTS WITH 'metrics.'
               | k: n[k]} AS properties,
              n.name AS name,
              string.join([node IN pathNodes[1..position + 1] | node.name], "/") AS fqn,
              id(a) AS cityId,
              CASE WHEN position > 0 THEN id(pathNodes[position - 1]) END AS parentId
            """,
            Map.of("tokenId", landscapeToken, "revisionIds", revisionIds));
    // StructureMapper derives child lists from parentId in linear time.
    return buildFlatLandscape(landscapeToken, result, TypeOfAnalysis.STATIC);
  }
}
