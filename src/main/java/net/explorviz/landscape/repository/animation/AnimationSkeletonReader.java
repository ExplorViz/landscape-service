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
    // Map lookup avoids testing every commit against a potentially large list of hashes.
    final Result files =
        session.query(
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})-[:CONTAINS]->(c:Commit)
            WHERE $ordinals[c.hash] IS NOT NULL
            MATCH (c)-[:CONTAINS]->(f:FileRevision)
            WITH f, min($ordinals[c.hash]) AS firstOrdinal,
              max($ordinals[c.hash]) AS lastOrdinal
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
            UNWIND $revisionIds AS revisionId
            MATCH (rep:FileRevision) WHERE id(rep) = revisionId
            // Enter the directory tree directly, avoiding the file's incoming commit edges.
            MATCH (parent:Directory)-[:CONTAINS]->(rep)
            MATCH p = (a:Application)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*0..]->(parent)
            WHERE (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a)
            // Omit the application's root directory from both the hierarchy and file paths.
            WITH a, [a] + nodes(p)[2..] + [rep] AS pathNodes
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
