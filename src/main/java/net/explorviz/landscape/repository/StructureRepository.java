package net.explorviz.landscape.repository;

import static net.explorviz.landscape.repository.AnimationCommitReader.fetchOrderedCommits;
import static net.explorviz.landscape.repository.StructureMapper.buildFlatLandscape;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
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
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.repository.AnimationCommitReader.CommitMeta;
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
  "PMD.TooManyMethods",
  "PMD.UselessParentheses"
})
@ApplicationScoped
public class StructureRepository {

  private static final FlatLandscapeMerger LANDSCAPE_MERGER = new FlatLandscapeMerger();

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
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a:Application|DatabaseSystem)
            OPTIONAL MATCH (a)-[:HAS_ROOT]->(rootDir:Directory)

            MATCH p = (a)-[:CONTAINS]->*(n)
            WHERE
              (rootDir IS NULL OR n <> rootDir) // Application root shouldn't become a district
              AND EXISTS {
                MATCH (n)
                  -[:CONTAINS]->*(end:FileRevision|DatabaseSystem|RPCService|RPCClient|HTTPEndpoint|HTTPClient|MessagingDestination|GenericTelemetryEntity)
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
            WITH *, [(n)-[:CONTAINS]->(c) WHERE c IN allMatchedNodes | id(c)] AS childrenIds

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
            Map.of("tokenId", landscapeToken));

    return buildFlatLandscape(landscapeToken, result, TypeOfAnalysis.RUNTIME);
  }

  /**
   * Static structure for an arbitrary number of (repository, commit) pairs. Every entry of {@code
   * $requests} carries a {@code key} that tags the rows it produces, so a whole batch of pairs is
   * served by a single round-trip.
   *
   * <p>The traversal starts at the file revisions of each commit and walks <em>up</em> to the
   * owning application root. Since a file revision has exactly one directory parent and a directory
   * exactly one parent, this only visits nodes that actually end up in the response. Descending
   * from the application roots instead would have to test every node of the repository against the
   * commit, which costs orders of magnitude more database hits. Restricting the applications to the
   * requested repository is implicit: the directory chain of a commit's file revision never leaves
   * the repository that commit belongs to.
   */
  private static final String STATIC_STRUCTURE_QUERY =
      """
      UNWIND $requests AS request
      MATCH (:Landscape {tokenId: $tokenId})
        -[:CONTAINS]->(:Repository {name: request.repositoryName})
        -[:CONTAINS]->(c:Commit {hash: request.commitHash})

      MATCH (c)-[:CONTAINS]->(file:FileRevision)
      MATCH chain = (file) ( ()<-[:CONTAINS]-(:Directory) ){1,} (appRoot:Directory)
      MATCH (appRoot)<-[:HAS_ROOT]-(a:Application)

      // [application, ...directories below the application root..., file]. The application root
      // itself is dropped, as it should not become a district.
      WITH request.key AS resultKey, a, [a] + reverse(nodes(chain))[1..] AS pathToFile

      // Every prefix of such a path denotes one node of the resulting landscape.
      UNWIND range(0, size(pathToFile) - 1) AS depth
      WITH DISTINCT resultKey, a, pathToFile[0..depth + 1] AS pathNodes

      WITH resultKey, a, pathNodes, last(pathNodes) AS n
      RETURN
        resultKey,
        id(n) AS id,
        labels(n) AS labels,
        properties(n) AS properties,
        n.name AS name,
        string.join([node IN pathNodes[1..] | node.name], "/") as fqn,
        id(a) AS cityId,
        id(pathNodes[-2]) AS parentId
      """;

  private static final String SINGLE_RESULT_KEY = "single";
  private static final String FIRST_RESULT_KEY = "first";
  private static final String SECOND_RESULT_KEY = "second";

  public FlatLandscapeDto fetchFlatLandscapeForStaticData(
      final Session session, final StaticDataRequest request) {

    final Map<String, FlatLandscapeDto> landscapes =
        fetchStaticLandscapes(
            session,
            request.landscapeToken(),
            List.of(
                staticRequest(SINGLE_RESULT_KEY, request.repositoryName(), request.commitHash())));

    return landscapeOrEmpty(landscapes, SINGLE_RESULT_KEY, request.landscapeToken());
  }

  public FlatLandscapeDto fetchCombinedFlatLandscape(
      final Session session, final CombinedStaticDataRequest request) {

    final Map<String, FlatLandscapeDto> landscapes =
        fetchStaticLandscapes(
            session,
            request.landscapeToken(),
            List.of(
                staticRequest(
                    FIRST_RESULT_KEY, request.repositoryName(), request.firstCommitHash()),
                staticRequest(
                    SECOND_RESULT_KEY, request.repositoryName(), request.secondCommitHash())));

    return LANDSCAPE_MERGER.merge(
        request.landscapeToken(),
        landscapeOrEmpty(landscapes, FIRST_RESULT_KEY, request.landscapeToken()),
        landscapeOrEmpty(landscapes, SECOND_RESULT_KEY, request.landscapeToken()));
  }

  /**
   * Loads structure for several repositories (each with either one commit or a pair for comparison)
   * and returns their union as one flat landscape. Intended for visualizing multiple repositories
   * together. All commits of the batch are read in a single query.
   */
  public FlatLandscapeDto fetchFlatLandscapeForEvolutionBatch(
      final Session session,
      final String landscapeToken,
      final List<RepositoryEvolutionSelectionDto> selections) {

    final List<Map<String, Object>> requests = new ArrayList<>();
    for (int i = 0; i < selections.size(); i++) {
      final RepositoryEvolutionSelectionDto selection = selections.get(i);
      final List<String> hashes = selection.commitHashes();
      for (int slot = 0; slot < hashes.size(); slot++) {
        requests.add(
            staticRequest(selectionKey(i, slot), selection.repositoryName(), hashes.get(slot)));
      }
    }

    final Map<String, FlatLandscapeDto> landscapes =
        fetchStaticLandscapes(session, landscapeToken, requests);

    final List<FlatLandscapeDto> parts = new ArrayList<>(selections.size());
    for (int i = 0; i < selections.size(); i++) {
      final FlatLandscapeDto first =
          landscapeOrEmpty(landscapes, selectionKey(i, 0), landscapeToken);

      if (selections.get(i).commitHashes().size() == 1) {
        parts.add(first);
      } else {
        parts.add(
            LANDSCAPE_MERGER.merge(
                landscapeToken,
                first,
                landscapeOrEmpty(landscapes, selectionKey(i, 1), landscapeToken)));
      }
    }
    return unionFlatLandscapes(landscapeToken, parts);
  }

  private Map<String, FlatLandscapeDto> fetchStaticLandscapes(
      final Session session,
      final String landscapeToken,
      final List<Map<String, Object>> requests) {

    if (requests.isEmpty()) {
      return Map.of();
    }

    final Result result =
        session.query(
            STATIC_STRUCTURE_QUERY, Map.of("tokenId", landscapeToken, "requests", requests));

    return StructureMapper.buildFlatLandscapesByKey(landscapeToken, result, TypeOfAnalysis.STATIC);
  }

  private static Map<String, Object> staticRequest(
      final String key, final String repositoryName, final String commitHash) {
    return Map.of("key", key, "repositoryName", repositoryName, "commitHash", commitHash);
  }

  private static String selectionKey(final int selectionIndex, final int commitSlot) {
    return selectionIndex + ":" + commitSlot;
  }

  private static FlatLandscapeDto landscapeOrEmpty(
      final Map<String, FlatLandscapeDto> landscapes,
      final String key,
      final String landscapeToken) {

    final FlatLandscapeDto landscape = landscapes.get(key);
    return landscape == null ? FlatLandscapeDto.newEmptyLandscape(landscapeToken) : landscape;
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

  public AnimationSkeletonDto fetchAnimationSkeleton(
      final Session session,
      final String landscapeToken,
      final String repositoryName,
      final long rangeFrom,
      final long rangeTo) {
    final List<CommitMeta> commits =
        fetchOrderedCommits(session, landscapeToken, repositoryName, rangeFrom, rangeTo);
    return AnimationSkeletonReader.fetchSkeleton(session, landscapeToken, repositoryName, commits);
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

  /**
   * Persists a user-facing display name for a city without changing the identity {@code name} used
   * by telemetry MERGE.
   *
   * @return the effective display name, or {@code null} if no matching city exists in the landscape
   */
  public String renameCity(
      final Session session, final String landscapeToken, final long cityId, final String newName) {
    final Result result =
        session.query(
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(city:Application|DatabaseSystem)
            WHERE id(city) = $cityId
            SET city.displayName = $name
            RETURN coalesce(city.displayName, city.name) AS name
            """,
            Map.of("tokenId", landscapeToken, "cityId", cityId, "name", newName));

    final Iterator<Map<String, Object>> rows = result.queryResults().iterator();
    if (!rows.hasNext()) {
      return null;
    }
    return (String) rows.next().get("name");
  }
}
