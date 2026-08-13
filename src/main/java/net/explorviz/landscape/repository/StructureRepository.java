package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.RepositoryEvolutionSelectionDto;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;
import net.explorviz.landscape.api.v3.model.landscape.AnimationFrameDeltaDto;
import net.explorviz.landscape.api.v3.model.landscape.AnimationFrameDto;
import net.explorviz.landscape.api.v3.model.landscape.AnimationSkeletonDto;
import net.explorviz.landscape.api.v3.model.landscape.AnimationWindowDeltaDto;
import net.explorviz.landscape.api.v3.model.landscape.AnimationWindowDto;
import net.explorviz.landscape.api.v3.model.landscape.BuildingChangeDto;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.BuildingStateDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FileHistoryDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class StructureRepository {

  private static final FlatLandscapeMerger LANDSCAPE_MERGER = new FlatLandscapeMerger();

  @Inject StructureMapper mapper;

  private record CommitMeta(String hash, long authorDate) {}

  public record StaticDataRequest(
      String landscapeToken, String repositoryName, String commitHash) {}

  public record CombinedStaticDataRequest(
      String landscapeToken,
      String repositoryName,
      String firstCommitHash,
      String secondCommitHash) {}

  public FlatLandscapeDto fetchFlatLandscapeForRuntimeData(
      final Session session, final String landscapeToken) {
    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        MATCH (func:Function)
        WHERE (l)-[:CONTAINS]->(:Trace)-[:CONTAINS]->(:Span)-[:REPRESENTS]->(func)

        MATCH p = (a:Application)-[:HAS_ROOT]->(root:Directory)-[:CONTAINS*0..]->(func)
        WHERE (l)-[:CONTAINS]->(a)

        WITH DISTINCT a, nodes(p) AS pathNodes

        UNWIND [a] + pathNodes AS n
        WITH DISTINCT n, a
        RETURN
          id(n) AS id,
          labels(n) AS labels,
          properties(n) AS properties,
          id(a) AS cityId,
          [(n)-[:HAS_ROOT|CONTAINS]->(m) | id(m)] AS childrenIds,
          [(n)<-[:HAS_ROOT|CONTAINS]-(p) | id(p)][0] AS parentId
        """;

    final Result result = session.query(query, Map.of("tokenId", landscapeToken));
    return mapper.buildFlatLandscape(landscapeToken, result, TypeOfAnalysis.RUNTIME, null);
  }

  public FlatLandscapeDto fetchFlatLandscapeForStaticData(
      final Session session, final StaticDataRequest request) {
    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(:Commit {hash: $commitHash})
        MATCH (c:Commit {hash: $commitHash})-[:CONTAINS]->(f:FileRevision)

        MATCH p = (a:Application)-[:HAS_ROOT]->(root:Directory)-[:CONTAINS*0..]->(f)
        WHERE (l)-[:CONTAINS]->(a)

        WITH DISTINCT a, nodes(p) AS pathNodes

        UNWIND [a] + pathNodes AS n
        WITH DISTINCT n, a
        RETURN
          id(n) AS id,
          labels(n) AS labels,
          properties(n) AS properties,
          id(a) AS cityId,
          [(n)-[:HAS_ROOT|CONTAINS]->(m) | id(m)] AS childrenIds,
          [(n)<-[:HAS_ROOT|CONTAINS]-(p) | id(p)][0] AS parentId
        """;

    final Result result =
        session.query(
            query,
            Map.of(
                "tokenId",
                request.landscapeToken(),
                "repoName",
                request.repositoryName(),
                "commitHash",
                request.commitHash()));
    return mapper.buildFlatLandscape(
        request.landscapeToken(), result, TypeOfAnalysis.STATIC, request.repositoryName());
  }

  public FlatLandscapeDto fetchCombinedFlatLandscape(
      final Session session, final CombinedStaticDataRequest request) {

    final FlatLandscapeDto first =
        fetchFlatLandscapeForStaticData(
            session,
            new StaticDataRequest(
                request.landscapeToken(), request.repositoryName(), request.firstCommitHash()));
    final FlatLandscapeDto second =
        fetchFlatLandscapeForStaticData(
            session,
            new StaticDataRequest(
                request.landscapeToken(), request.repositoryName(), request.secondCommitHash()));

    return LANDSCAPE_MERGER.merge(request.landscapeToken(), first, second);
  }

  /**
   * Loads structure for several repositories (each with either one commit or a pair for comparison)
   * and returns their union as one flat landscape. Intended for visualizing multiple repositories
   * together.
   */
  public FlatLandscapeDto fetchFlatLandscapeForEvolutionBatch(
      final Session session,
      final String landscapeToken,
      final List<RepositoryEvolutionSelectionDto> selections) {

    final List<FlatLandscapeDto> parts = new ArrayList<>();
    for (final RepositoryEvolutionSelectionDto sel : selections) {
      parts.add(fetchPartForSelection(session, landscapeToken, sel));
    }
    return unionFlatLandscapes(landscapeToken, parts);
  }

  private FlatLandscapeDto fetchPartForSelection(
      final Session session,
      final String landscapeToken,
      final RepositoryEvolutionSelectionDto sel) {
    final List<String> hashes = sel.commitHashes();
    if (hashes.size() == 1) {
      return fetchFlatLandscapeForStaticData(
          session, new StaticDataRequest(landscapeToken, sel.repositoryName(), hashes.get(0)));
    }
    return fetchCombinedFlatLandscape(
        session,
        new CombinedStaticDataRequest(
            landscapeToken, sel.repositoryName(), hashes.get(0), hashes.get(1)));
  }

  private FlatLandscapeDto unionFlatLandscapes(
      final String landscapeToken, final List<FlatLandscapeDto> parts) {

    final Map<String, CityDto> cities = new HashMap<>();
    final Map<String, DistrictDto> districts = new HashMap<>();
    final Map<String, BuildingDto> buildings = new HashMap<>();

    for (final FlatLandscapeDto part : parts) {
      cities.putAll(part.cities());
      districts.putAll(part.districts());
      buildings.putAll(part.buildings());
    }

    return new FlatLandscapeDto(landscapeToken, cities, districts, buildings);
  }

  /**
   * Builds an ordered sequence of flat landscape for every consecutive commit pair in the given
   * repository, used for commit-based animation. Each entry represents the structural diff between
   * one commit and its predesessor, with values set relative to later commit.
   */
  /*public List<AnimationFrameDto> fetchFlatLandscapeForAnimation(
      final Session session, final String landscapeToken, final String repositoryName) {

    final List<CommitMeta> commits =
        fetchOrderedCommits(session, landscapeToken, repositoryName);

    if (commits.isEmpty()) {
      return List.of();
    }

    final List<AnimationFrameDto> frames = new ArrayList<>();

    // First commit
    final CommitMeta first = commits.get(0);
    final FlatLandscapeDto firstSnapshot =
        fetchFlatLandscapeForStaticData(
            session, new StaticDataRequest(landscapeToken, repositoryName, first.hash()));
    final FlatLandscapeDto emptyBaseline =
        new FlatLandscapeDto(landscapeToken, Map.of(), Map.of(), Map.of());
    final FlatLandscapeDto firstFrame =
        LANDSCAPE_MERGER.merge(landscapeToken, emptyBaseline, firstSnapshot);
    frames.add(new AnimationFrameDto(first.hash(), first.authorDate(), 0, firstFrame));

    // Divs between commits
    for (int i = 1; i < commits.size(); i++) {
      final CommitMeta target = commits.get(i);
      frames.add(
          new AnimationFrameDto(
              target.hash(),
              target.authorDate(),
              i,
              fetchCombinedFlatLandscape(
                  session,
                  new CombinedStaticDataRequest(
                      landscapeToken, repositoryName, commits.get(i - 1).hash(), target.hash()))));
    }

    return frames;
  }*/

  public List<FileHistoryDto> fetchFileHistory(final Session session, final long fileRevisionId) {
    final String query =
        """
        MATCH (clicked:FileRevision) WHERE id(clicked) = $id
        MATCH (dir:Directory)-[:CONTAINS]->(clicked)
        MATCH (dir)-[:CONTAINS]->(rev:FileRevision) WHERE rev.name = clicked.name
        MATCH (c:Commit)-[r:ADDED|MODIFIED|DELETED]->(rev)
        RETURN c.hash AS hash, coalesce(c.authorDate, 0) AS date, type(r) AS action
        ORDER BY date ASC
        """;
    final Result result = session.query(query, Map.of("id", fileRevisionId));

    final List<FileHistoryDto> entries = new ArrayList<>();
    result.forEach(
        row -> {
          final Object date = row.get("date");
          entries.add(
              new FileHistoryDto(
                  (String) row.get("hash"),
                  date instanceof Number n ? n.longValue() : 0L,
                  (String) row.get("action")));
        });
    return entries;
  }

  private List<CommitMeta> fetchOrderedCommits(
      final Session session, final String landscapeToken, final String repositoryName) {
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        WHERE coalesce(c.authorDate, 0) <> 0
        RETURN c.hash AS hash, c.authorDate AS authorDate
        ORDER BY c.authorDate ASC, c.hash ASC
        """;

    final Result result =
        session.query(query, Map.of("tokenId", landscapeToken, "repoName", repositoryName));

    final List<CommitMeta> commits = new ArrayList<>();
    result.forEach(
        row -> {
          final Object date = row.get("authorDate");
          final long authorDate = date instanceof Number n ? n.longValue() : 0L;
          commits.add(new CommitMeta((String) row.get("hash"), authorDate));
        });
    return commits;
  }

  public AnimationWindowDto fetchAnimationWindow(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final int start,
      final int count,
      final int granularity,
      final String groupBy,
      final long bucketSize) {

    final List<CommitMeta> commits = fetchOrderedCommits(session, landscapeToken, repositoryName);

    final int commitCount = commits.size();
    if (commitCount == 0) {
      return new AnimationWindowDto(0, 0, List.of());
    }
    // final int granul = Math.max(1, granularity);
    final List<Integer> targets =
        "time".equals(groupBy)
            ? timeBucketTargets(commits, Math.max(1, bucketSize))
            : commitBucketTargets(commitCount, Math.max(1, granularity));

    final int totalFrames = targets.size();

    final int from = Math.max(0, start);
    if (from >= totalFrames) {
      return new AnimationWindowDto(totalFrames, totalFrames, List.of());
    }
    final int to = count < 0 ? totalFrames : Math.min(totalFrames, from + count);

    final List<AnimationFrameDto> frames = new ArrayList<>();
    for (int i = from; i < to; i++) {
      final CommitMeta target = commits.get(targets.get(i));
      final FlatLandscapeDto landscape;
      if (i == 0) {
        final FlatLandscapeDto snapshot =
            fetchFlatLandscapeForStaticData(
                session, new StaticDataRequest(landscapeToken, repositoryName, target.hash()));
        landscape =
            LANDSCAPE_MERGER.merge(
                landscapeToken,
                new FlatLandscapeDto(landscapeToken, Map.of(), Map.of(), Map.of()),
                snapshot);
      } else {
        final CommitMeta prevLast = commits.get(targets.get(i - 1));
        landscape =
            fetchCombinedFlatLandscape(
                session,
                new CombinedStaticDataRequest(
                    landscapeToken, repositoryName, prevLast.hash(), target.hash()));
      }
      frames.add(new AnimationFrameDto(target.hash(), target.authorDate(), i, landscape));
    }
    return new AnimationWindowDto(totalFrames, from, frames);
  }

  public AnimationWindowDeltaDto fetchAnimationDeltaWindow(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final int start,
      final int count,
      final int granularity,
      final String groupBy,
      final long bucketSize) {

    final List<CommitMeta> commits = fetchOrderedCommits(session, landscapeToken, repositoryName);
    final int commitCount = commits.size();
    if (commitCount == 0) {
      return new AnimationWindowDeltaDto(0, 0, List.of());
    }

    final List<Integer> targets =
        "time".equals(groupBy)
            ? timeBucketTargets(commits, Math.max(1, bucketSize))
            : commitBucketTargets(commitCount, Math.max(1, granularity));

    final int totalFrames = targets.size();
    final int from = Math.max(0, start);
    if (from >= totalFrames) {
      return new AnimationWindowDeltaDto(totalFrames, totalFrames, List.of());
    }
    final int to = count < 0 ? totalFrames : Math.min(totalFrames, from + count);

    final Map<String, Map<String, String>> presentByCommit =
        fetchPresentSets(session, landscapeToken, repositoryName);

    final List<AnimationFrameDeltaDto> frames = new ArrayList<>();
    final Map<String, Integer> lastChangeOrdinal = new HashMap<>();
    final Map<String, Long> lastChangeDate = new HashMap<>();
    Map<String, String> prevPresent = Map.of();

    for (int i = 0; i < to; i++) {
      final CommitMeta target = commits.get(targets.get(i));
      final Map<String, String> curPresent = presentByCommit.getOrDefault(target.hash(), Map.of());
      final List<BuildingChangeDto> changes = diffPresentSets(prevPresent, curPresent);

      for (final BuildingChangeDto change : changes) {
        if (CommitComparison.REMOVED.toString().equals(change.action())) {
          lastChangeOrdinal.remove(change.fqn());
          lastChangeDate.remove(change.fqn());
        } else {
          lastChangeOrdinal.put(change.fqn(), i);
          lastChangeDate.put(change.fqn(), target.authorDate());
        }
      }

      if (i == from) {
        frames.add(
            new AnimationFrameDeltaDto(
                target.hash(),
                target.authorDate(),
                i,
                true,
                buildKeyframeState(curPresent, lastChangeOrdinal, lastChangeDate),
                changes));
      } else if (i > from) {
        frames.add(
            new AnimationFrameDeltaDto(
                target.hash(), target.authorDate(), i, false, null, changes));
      }
      prevPresent = curPresent;
    }
    return new AnimationWindowDeltaDto(totalFrames, from, frames);
  }

  private Map<String, Map<String, String>> fetchPresentSets(
      final Session session, final String landscapeToken, final String repositoryName) {
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        MATCH (c)-[:CONTAINS]->(f:FileRevision)
        RETURN c.hash AS hash, f.filePath AS fqn, f.hash AS fileHash
        """;

    final Result result =
        session.query(query, Map.of("tokenId", landscapeToken, "repoName", repositoryName));

    final Map<String, Map<String, String>> presentByCommit = new HashMap<>();
    result.forEach(
        row -> {
          final String hash = (String) row.get("hash");
          final String fqn = (String) row.get("fqn");
          if (hash == null || fqn == null) {
            return;
          }
          presentByCommit
              .computeIfAbsent(hash, key -> new HashMap<>())
              .put(fqn, (String) row.get("fileHash"));
        });
    return presentByCommit;
  }

  private List<BuildingChangeDto> diffPresentSets(
      final Map<String, String> prev, final Map<String, String> cur) {

    final List<BuildingChangeDto> changes = new ArrayList<>();
    cur.forEach(
        (fqn, fileHash) -> {
          final String prevHash = prev.get(fqn);
          if (prevHash == null) {
            changes.add(new BuildingChangeDto(fqn, CommitComparison.ADDED.toString()));
          } else if (!prevHash.equals(fileHash)) {
            changes.add(new BuildingChangeDto(fqn, CommitComparison.MODIFIED.toString()));
          }
        });
    prev.forEach(
        (fqn, fileHash) -> {
          if (!cur.containsKey(fqn)) {
            changes.add(new BuildingChangeDto(fqn, CommitComparison.REMOVED.toString()));
          }
        });
    return changes;
  }

  private List<BuildingStateDto> buildKeyframeState(
      final Map<String, String> present,
      final Map<String, Integer> lastChangeOrdinal,
      final Map<String, Long> lastChangeDate) {
    final List<BuildingStateDto> state = new ArrayList<>();
    present
        .keySet()
        .forEach(
            fqn ->
                state.add(
                    new BuildingStateDto(
                        fqn,
                        lastChangeOrdinal.getOrDefault(fqn, 0),
                        lastChangeDate.getOrDefault(fqn, 0L))));
    return state;
  }

  private Map<String, Integer> computeFqnFirstOrdinals(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final List<CommitMeta> commits) {
    final Map<String, Integer> ordinalByHash = new HashMap<>();
    for (int i = 0; i < commits.size(); i++) {
      ordinalByHash.put(commits.get(i).hash(), i);
    }

    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        MATCH (c)-[:CONTAINS]->(f:FileRevision)
        MATCH p = (a:Application)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*0..]->(f)
        WHERE (l)-[:CONTAINS]->(a)
        RETURN apoc.text.join([node IN nodes(p)[2..] | node.name], "/") AS fqn, c.hash AS hash
        """;
    final Result result =
        session.query(query, Map.of("tokenId", landscapeToken, "repoName", repositoryName));

    final Map<String, Integer> fqnToFirstOrdinal = new HashMap<>();
    result.forEach(
        row -> {
          final String fqn = (String) row.get("fqn");
          final Integer ordinal = ordinalByHash.get((String) row.get("hash"));
          if (fqn != null && ordinal != null) {
            fqnToFirstOrdinal.merge(fqn, ordinal, Math::min);
          }
        });
    return fqnToFirstOrdinal;
  }

  public AnimationSkeletonDto fetchAnimationSkeleton(
      final Session session, final String landscapeToken, final String repositoryName) {
    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        MATCH (c)-[:CONTAINS]->(f:FileRevision)

        MATCH p = (a:Application)-[:HAS_ROOT]->(root:Directory)-[:CONTAINS*0..]->(f)
        WHERE (l)-[:CONTAINS]->(a)

        WITH DISTINCT a, nodes(p) AS pathNodes

        UNWIND [a] + pathNodes AS n
        WITH DISTINCT n, a
        RETURN
          id(n) AS id,
          labels(n) AS labels,
          properties(n) AS properties,
          id(a) AS cityId,
          [(n)-[:HAS_ROOT|CONTAINS]->(m) | id(m)] AS childrenIds,
          [(n)<-[:HAS_ROOT|CONTAINS]-(p) | id(p)][0] AS parentId
        """;

    final Result result =
        session.query(query, Map.of("tokenId", landscapeToken, "repoName", repositoryName));
    final FlatLandscapeDto landscape =
        deduplicateBuildingsByFqn(
            mapper.buildFlatLandscape(
                landscapeToken, result, TypeOfAnalysis.STATIC, repositoryName));
    final List<CommitMeta> commits = fetchOrderedCommits(session, landscapeToken, repositoryName);
    final List<String> orderedCommitHashes = commits.stream().map(CommitMeta::hash).toList();
    final List<Long> orderedCommitTimeStamps =
        commits.stream().map(CommitMeta::authorDate).toList();
    final Map<String, Integer> fqnToFirstOrdinal =
        computeFqnFirstOrdinals(session, landscapeToken, repositoryName, commits);

    return new AnimationSkeletonDto(
        landscape, fqnToFirstOrdinal, orderedCommitHashes, orderedCommitTimeStamps);
  }

  private FlatLandscapeDto deduplicateBuildingsByFqn(final FlatLandscapeDto raw) {
    final Map<String, String> fqnToCanonicalId = new HashMap<>();
    final Map<String, String> idToFqn = new HashMap<>();
    final Map<String, BuildingDto> buildings = new HashMap<>();
    for (final BuildingDto b : raw.buildings().values()) {
      final String id = b.flatBaseModel().id();
      final String fqn = b.flatBaseModel().fqn();
      idToFqn.put(id, fqn);
      if (fqn == null) {
        buildings.put(id, b);
      } else if (!fqnToCanonicalId.containsKey(fqn)) {
        fqnToCanonicalId.put(fqn, id);
        buildings.put(id, b);
      }
    }

    final java.util.function.Function<String, String> canonical =
        bid -> {
          final String fqn = idToFqn.get(bid);
          return fqn == null ? bid : fqnToCanonicalId.getOrDefault(fqn, bid);
        };
    final Map<String, DistrictDto> districts = new HashMap<>();
    raw.districts()
        .forEach(
            (id, d) ->
                districts.put(
                    id,
                    new DistrictDto(
                        d.flatBaseModel(),
                        d.parentCityId(),
                        d.parentDistrictId(),
                        d.districtIds(),
                        d.buildingIds().stream().map(canonical).distinct().toList())));

    final Map<String, CityDto> cities = new HashMap<>();
    raw.cities()
        .forEach(
            (id, c) ->
                cities.put(
                    id,
                    new CityDto(
                        c.flatBaseModel(),
                        c.districtIds(),
                        c.buildingIds().stream().map(canonical).distinct().toList(),
                        c.allContainedDistrictIds(),
                        c.allContainedBuildingIds().stream().map(canonical).distinct().toList())));

    return new FlatLandscapeDto(raw.landscapeToken(), cities, districts, buildings);
  }

  // Helper Functions
  private List<Integer> commitBucketTargets(final int commitCount, final int granul) {
    final int totalFrames = (commitCount + granul - 1) / granul;
    final List<Integer> targets = new ArrayList<>();
    for (int i = 0; i < totalFrames; i++) {
      targets.add(Math.min((i + 1) * granul, commitCount) - 1);
    }
    return targets;
  }

  private List<Integer> timeBucketTargets(final List<CommitMeta> commits, final long bucketSize) {
    final long t0 = commits.get(0).authorDate();
    final List<Integer> targets = new ArrayList<>();
    for (int i = 0; i < commits.size(); i++) {
      final long bucket = (commits.get(i).authorDate() - t0) / bucketSize;
      final boolean lastOfBucket =
          i == commits.size() - 1 || (commits.get(i + 1).authorDate() - t0) / bucketSize != bucket;
      if (lastOfBucket) {
        targets.add(i);
      }
    }
    return targets;
  }
}
