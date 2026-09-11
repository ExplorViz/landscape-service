package net.explorviz.landscape.repository;

import static net.explorviz.landscape.repository.StructureMapper.buildFlatLandscape;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.CognitiveComplexity",
  "PMD.CouplingBetweenObjects",
  "PMD.CyclomaticComplexity",
  "PMD.ExcessiveParameterList",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.NPathComplexity",
  "PMD.NcssCount",
  "PMD.SimplifyBooleanReturns",
  "PMD.TooManyMethods",
  "PMD.UseUnderscoresInNumericLiterals",
  "PMD.UselessParentheses"
})
@ApplicationScoped
public class StructureRepository {

  private static final FlatLandscapeMerger LANDSCAPE_MERGER = new FlatLandscapeMerger();
  private static final int SCOPED_ROUTE_COMMIT_CAP = 1500;
  private static final long SCOPED_ROUTE_PAIR_CAP = 3000000L;

  private record CommitMeta(String hash, long authorDate) {}

  private record WalkState(
      int frameIndex,
      Map<String, Integer> lastChangeOrdinal,
      Map<String, Long> lastChangeDate,
      Map<String, String> present,
      int targetId) {}

  private final Map<String, WalkState> walkStateCache = new ConcurrentHashMap<>();

  public record StaticDataRequest(
      String landscapeToken, String repositoryName, String commitHash) {}

  public record CombinedStaticDataRequest(
      String landscapeToken,
      String repositoryName,
      String firstCommitHash,
      String secondCommitHash) {}

  public FlatLandscapeDto fetchFlatLandscapeForRuntimeData(
      final Session session, final String landscapeToken) {
    final Result result =
        session.query(
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a:Application)
            OPTIONAL MATCH (a)-[:HAS_ROOT]->(rootDir:Directory)

            MATCH p = (a)-[:CONTAINS]->*(n)
            WHERE
              (rootDir IS NULL OR n <> rootDir) // Application root shouldn't become a district
              AND EXISTS {
                MATCH (n)
                  -[:CONTAINS]->*(end:FileRevision|RPCService|HTTPEndpoint|GenericTelemetryEntity)
                WHERE
                  end.telemetryKey IS NOT NULL
                  AND NOT (:Commit)-[:CONTAINS]->(end)
              }

            // Remove application root directory from path, as it should not become a district.
            WITH a, n, [x IN nodes(p) WHERE coalesce(x <> rootDir, true)] AS pathNodes

            WITH a, collect(DISTINCT n) AS allMatchedNodes, collect(DISTINCT pathNodes) AS matches

            UNWIND matches AS pathNodes
            WITH a, allMatchedNodes, pathNodes, last(pathNodes) AS n

            // Determine child IDs, where only previously matched nodes should qualify.
            // Children of app root directory should be given directly to the containing scope.
            WITH *, CASE
              WHEN n:Scope THEN [
                (n)-[:CONTAINS*0..1]->(:Directory|Scope)-[:CONTAINS]->(c)
                WHERE c IN allMatchedNodes | id(c)
              ]
              ELSE [(n)-[:CONTAINS]->(c) WHERE c IN allMatchedNodes | id(c)]
            END AS childrenIds

            RETURN
              id(n) AS id,
              labels(n) AS labels,
              properties(n) AS properties,
              coalesce(n.name, n.route) AS name,
              string.join([node IN pathNodes[1..] | node.name], "/") as fqn,
              id(a) AS cityId,
              childrenIds,
              id(pathNodes[-2]) AS parentId
            """,
            Map.of("tokenId", landscapeToken));

    return buildFlatLandscape(landscapeToken, result, TypeOfAnalysis.RUNTIME);
  }

  public FlatLandscapeDto fetchFlatLandscapeForStaticData(
      final Session session, final StaticDataRequest request) {
    final Result result =
        session.query(
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(r:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit {hash: $commitHash})

            // Find relevant applications
            MATCH (a:Application)-[:HAS_ROOT]->(rootDir:Directory)
            WHERE (rootDir)<-[:CONTAINS*0..]-(:Directory)<-[:HAS_ROOT]-(r)

            // Find path from application to all required nodes
            MATCH p = (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*0..]->(n)
            WHERE (n)-[:CONTAINS*0..]->(:FileRevision)<-[:CONTAINS]-(c)

            // Remove application root directory from path, as it should not become a district.
            WITH a, n, nodes(p)[0] + nodes(p)[2..] AS pathNodes

            WITH a, collect(DISTINCT n) AS allMatchedNodes, collect(DISTINCT pathNodes) AS matches

            UNWIND matches AS pathNodes
            WITH a, allMatchedNodes, pathNodes, last(pathNodes) AS n

            // Determine child IDs, where only previously matched nodes should qualify.
            // Children of app root directory should be given directly to the application instead.
            WITH *, [
              (n)-[:HAS_ROOT*0..1]->()-[:CONTAINS]->(c) WHERE c IN allMatchedNodes | id(c)
            ] AS childrenIds

            RETURN
              id(n) AS id,
              labels(n) AS labels,
              properties(n) AS properties,
              n.name AS name,
              string.join([node IN pathNodes[1..] | node.name], "/") as fqn,
              id(a) AS cityId,
              childrenIds,
              id(pathNodes[-2]) AS parentId
            """,
            Map.of(
                "tokenId", request.landscapeToken(),
                "repoName", request.repositoryName(),
                "commitHash", request.commitHash()));
    return buildFlatLandscape(request.landscapeToken(), result, TypeOfAnalysis.STATIC);
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
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo) {
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        WHERE coalesce(c.authorDate, 0) <> 0
          AND ($rangeFrom = 0 OR c.authorDate >= $rangeFrom)
          AND ($rangeTo = 0 OR c.authorDate <= $rangeTo)
        RETURN c.hash AS hash, c.authorDate AS authorDate
        ORDER BY c.authorDate ASC, c.hash ASC
        """;

    final Result result =
        session.query(
            query,
            Map.of(
                "tokenId",
                landscapeToken,
                "repoName",
                repositoryName,
                "rangeFrom",
                rangeFrom,
                "rangeTo",
                rangeTo));

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

    final List<CommitMeta> commits =
        fetchOrderedCommits(session, landscapeToken, repositoryName, 0, 0);

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
      final long bucketSize,
      final long agingWindow,
      final long rangeFrom,
      final long rangeTo) {

    final List<CommitMeta> commits =
        fetchOrderedCommits(session, landscapeToken, repositoryName, rangeFrom, rangeTo);
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

    final boolean timeMode = "time".equals(groupBy);
    final long bucket = Math.max(1, bucketSize);
    final long firstTs = commits.get(0).authorDate();
    final long lastTs = commits.get(commits.size() - 1).authorDate();

    final String cacheKey =
        landscapeToken
            + '|'
            + repositoryName
            + '|'
            + groupBy
            + '|'
            + granularity
            + '|'
            + bucket
            + '|'
            + agingWindow;
    final WalkState cached = walkStateCache.get(cacheKey);

    final int walkFrom;
    final Map<String, Integer> lastChangeOrdinal;
    final Map<String, Long> lastChangeDate;
    Map<String, String> prevPresent;
    int prevTargetId;
    final int lookback = Math.max(1, lookbackFrames(commits, targets, from, timeMode, agingWindow));
    final int minStart = Math.max(0, from - lookback);

    if (cached != null && cached.frameIndex() < from && cached.frameIndex() + 1 >= minStart) {
      walkFrom = cached.frameIndex() + 1;
      lastChangeOrdinal = new HashMap<>(cached.lastChangeOrdinal());
      lastChangeDate = new HashMap<>(cached.lastChangeDate());
      prevPresent = cached.present();
      prevTargetId = cached.targetId();
    } else {
      walkFrom = minStart;
      lastChangeOrdinal = new HashMap<>();
      lastChangeDate = new HashMap<>();
      prevPresent = Map.of();
      prevTargetId = minStart > 0 ? targets.get(minStart - 1) : -1;
    }

    final List<String> neededHashes = new ArrayList<>();
    for (int i = walkFrom; i < to; i++) {
      neededHashes.add(commits.get(targets.get(i)).hash());
    }
    final Map<String, Map<String, String>> presentByCommit =
        fetchPresentSets(session, landscapeToken, repositoryName, neededHashes);

    final List<AnimationFrameDeltaDto> frames = new ArrayList<>();
    for (int i = walkFrom; i < to; i++) {
      final CommitMeta target = commits.get(targets.get(i));
      final int frameCommitCount = targets.get(i) - prevTargetId;
      final long tsFrom;
      final long tsTo;
      if (timeMode) {
        tsFrom = firstTs + bucket * i;
        tsTo = Math.min(firstTs + bucket * (i + 1L), lastTs);
      } else {
        tsFrom = commits.get(Math.max(0, prevTargetId + 1)).authorDate();
        tsTo = target.authorDate();
      }
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
                tsFrom,
                tsTo,
                frameCommitCount,
                buildKeyframeState(curPresent, lastChangeOrdinal, lastChangeDate),
                changes));
      } else if (i > from) {
        frames.add(
            new AnimationFrameDeltaDto(
                target.hash(),
                target.authorDate(),
                i,
                false,
                tsFrom,
                tsTo,
                frameCommitCount,
                null,
                changes));
      }
      prevPresent = curPresent;
      prevTargetId = targets.get(i);
    }
    if (walkStateCache.size() > 8) {
      walkStateCache.clear();
    }
    walkStateCache.put(
        cacheKey,
        new WalkState(to - 1, lastChangeOrdinal, lastChangeDate, prevPresent, prevTargetId));
    return new AnimationWindowDeltaDto(totalFrames, from, frames);
  }

  private int lookbackFrames(
      final List<CommitMeta> commits,
      final List<Integer> targets,
      final int from,
      final boolean timeMode,
      final long agingWindow) {
    if (!timeMode) {
      return (int) Math.min(agingWindow, Integer.MAX_VALUE);
    }
    final long cutoff = commits.get(targets.get(from)).authorDate() - agingWindow;
    for (int i = from; i >= 0; i--) {
      if (commits.get(targets.get(i)).authorDate() <= cutoff) {
        return from - i;
      }
    }
    return from;
  }

  private Map<String, Map<String, String>> fetchPresentSets(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final Collection<String> commitHashes) {
    if (commitHashes.isEmpty()) {
      return Map.of();
    }
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        WHERE c.hash IN $hashes
        MATCH (c)-[:CONTAINS]->(f:FileRevision)
        RETURN c.hash AS hash, f.filePath AS fqn, f.hash AS fileHash
        """;

    final Result result =
        session.query(
            query,
            Map.of(
                "tokenId",
                landscapeToken,
                "repoName",
                repositoryName,
                "hashes",
                List.copyOf(commitHashes)));

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
      final List<CommitMeta> commits,
      final long rangeFrom,
      final long rangeTo) {
    final Map<Long, Integer> ordinalByDate = new HashMap<>();
    for (int i = 0; i < commits.size(); i++) {
      ordinalByDate.putIfAbsent(commits.get(i).authorDate(), i);
    }

    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        WHERE coalesce(c.authorDate, 0) <> 0
            AND ($rangeFrom = 0 OR c.authorDate >= $rangeFrom)
            AND ($rangeTo = 0 OR c.authorDate <= $rangeTo)
        MATCH (c)-[:CONTAINS]->(f:FileRevision)
        RETURN f.filePath AS fqn, min(c.authorDate) AS firstAppearance
        """;
    final Result result =
        session.query(
            query,
            Map.of(
                "tokenId",
                landscapeToken,
                "repoName",
                repositoryName,
                "rangeFrom",
                rangeFrom,
                "rangeTo",
                rangeTo));

    final Map<String, Integer> fqnToFirstOrdinal = new HashMap<>();
    result.forEach(
        row -> {
          final String fqn = (String) row.get("fqn");
          final Object date = row.get("firstAppearance");
          if (fqn == null || !(date instanceof Number n)) {
            return;
          }
          final Integer ordinal = ordinalByDate.get(n.longValue());
          if (ordinal != null) {
            fqnToFirstOrdinal.put(fqn, ordinal);
          }
        });
    return fqnToFirstOrdinal;
  }

  public AnimationSkeletonDto fetchAnimationSkeleton(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo) {

    final List<CommitMeta> commits =
        fetchOrderedCommits(session, landscapeToken, repositoryName, rangeFrom, rangeTo);
    final boolean scoped =
        useScopedRoute(session, landscapeToken, repositoryName, rangeFrom, rangeTo, commits);
    final FlatLandscapeDto landscape =
        scoped
            ? buildScopedSkeleton(session, landscapeToken, repositoryName, rangeFrom, rangeTo)
            : buildFullSkeleton(session, landscapeToken, repositoryName);
    final List<String> orderedCommitHashes = commits.stream().map(CommitMeta::hash).toList();
    final List<Long> orderedCommitTimeStamps =
        commits.stream().map(CommitMeta::authorDate).toList();
    final Map<String, Integer> fqnToFirstOrdinal =
        computeFqnFirstOrdinals(
            session, landscapeToken, repositoryName, commits, rangeFrom, rangeTo);

    return new AnimationSkeletonDto(
        landscape, fqnToFirstOrdinal, orderedCommitHashes, orderedCommitTimeStamps);
  }

  private boolean useScopedRoute(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo,
      final List<CommitMeta> commits) {
    if (rangeFrom == 0 && rangeTo == 0) {
      return false;
    }
    if (commits.size() > SCOPED_ROUTE_COMMIT_CAP) {
      return false;
    }
    return countCommitFilePairs(session, landscapeToken, repositoryName, rangeFrom, rangeTo)
        <= SCOPED_ROUTE_PAIR_CAP;
  }

  private long countCommitFilePairs(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo) {
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        WHERE coalesce(c.authorDate, 0) <> 0
          AND ($rangeFrom = 0 OR c.authorDate >= $rangeFrom)
          AND ($rangeTo = 0 OR c.authorDate <= $rangeTo)
        RETURN sum(COUNT { (c)-[:CONTAINS]->() }) AS pairs
        """;

    final Result result =
        session.query(
            query,
            Map.of(
                "tokenId", landscapeToken,
                "repoName", repositoryName,
                "rangeFrom", rangeFrom,
                "rangeTo", rangeTo));

    for (final Map<String, Object> row : result) {
      if (row.get("pairs") instanceof Number pairs) {
        return pairs.longValue();
      }
    }
    return Long.MAX_VALUE;
  }

  private FlatLandscapeDto buildFullSkeleton(
      final Session session, final String landscapeToken, final String repositoryName) {
    final Result result =
        session.query(
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a:Application)
            MATCH p = (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(n:Directory|FileRevision)
            WHERE (n)-[:CONTAINS*0..]->(:FileRevision {repoName: $repoName})

            // Remove application root directory from path, as it should not become a district.
            WITH a, n, nodes(p)[0] + nodes(p)[2..] AS pathNodes

            WITH a,
              [a] + collect(DISTINCT n) AS allMatchedNodes,
              [[a]] + collect(DISTINCT pathNodes) AS matches

            UNWIND matches AS pathNodes
            WITH a, allMatchedNodes, pathNodes, last(pathNodes) AS n

            // Determine child IDs, where only previously matched nodes should qualify.
            // Children of app root directory should be given directly to the application instead.
            WITH *, [
              (n)-[:HAS_ROOT*0..1]->()-[:CONTAINS]->(m) WHERE m IN allMatchedNodes | id(m)
            ] AS childrenIds

            RETURN
              id(n) AS id,
              labels(n) AS labels,
              apoc.map.fromPairs(
                [k IN keys(n)
                 WHERE k = 'language' OR k STARTS WITH 'metrics.'
                 | [k, n[k]]]
              ) AS properties,
              n.name AS name,
              string.join([node IN pathNodes[1..] | node.name], "/") as fqn,
              id(a) AS cityId,
              childrenIds,
              id(pathNodes[-2]) AS parentId
            """,
            Map.of(
                "tokenId", landscapeToken,
                "repoName", repositoryName));
    return deduplicateBuildingsByFqn(
        buildFlatLandscape(landscapeToken, result, TypeOfAnalysis.STATIC));
  }

  private FlatLandscapeDto buildScopedSkeleton(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo) {

    final Result result =
        session.query(
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit)
            WHERE
              coalesce(c.authorDate, 0) <> 0
              AND ($rangeFrom = 0 OR c.authorDate >= $rangeFrom)
              AND ($rangeTo = 0 OR c.authorDate <= $rangeTo)
            MATCH (c)-[:CONTAINS]->(f:FileRevision)
            WITH f, c.authorDate AS d
            ORDER BY d DESC
            WITH f.filePath AS filePath, head(collect(f)) AS rep

            // Match all relevant ancestor nodes in the application structure.
            // The application's root directory is excluded since should not become a district.
            MATCH (n:Application|Directory|FileRevision)-[:CONTAINS|HAS_ROOT]->*(rep)
            WHERE NOT (:Application)-[:HAS_ROOT]->(n)

            WITH collect(DISTINCT n) AS allMatchedNodes
            UNWIND allMatchedNodes AS n

            // Find full path from application to node
            CALL (n) {
              MATCH p = (a:Application)-[:CONTAINS|HAS_ROOT]->*(n)
              RETURN p, a
              LIMIT 1
            }

            // Remove application root directory from the path, as it should not contribute to fqn
            WITH a, n, allMatchedNodes, nodes(p)[0] + nodes(p)[2..] AS pathNodes

            // When determining child IDs, include children of app root directory in app's children
            WITH *, [
              (n)-[:HAS_ROOT*0..1]->()-[:CONTAINS]->(m) WHERE m IN allMatchedNodes | id(m)
            ] AS childrenIds

            RETURN
              id(n) AS id,
              labels(n) AS labels,
              properties(n) AS properties,
              n.name AS name,
              string.join([node IN pathNodes[1..] | node.name], "/") as fqn,
              id(a) AS cityId,
              childrenIds,
              id(pathNodes[-2]) AS parentId
            """,
            Map.of(
                "tokenId", landscapeToken,
                "repoName", repositoryName,
                "rangeFrom", rangeFrom,
                "rangeTo", rangeTo));

    return buildFlatLandscape(landscapeToken, result, TypeOfAnalysis.STATIC);
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
    final long tEnd = commits.get(commits.size() - 1).authorDate();
    final int frameCount = (int) Math.max(1, ((tEnd - t0) + bucketSize - 1) / bucketSize);
    final List<Integer> targets = new ArrayList<>();
    int cursor = 0;
    for (int i = 0; i < frameCount; i++) {
      final long intervalEnd = t0 + bucketSize * (i + 1L);
      while (cursor + 1 < commits.size() && commits.get(cursor + 1).authorDate() <= intervalEnd) {
        cursor++;
      }
      targets.add(cursor);
    }
    return targets;
  }
}
