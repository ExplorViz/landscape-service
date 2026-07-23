package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.RepositoryEvolutionSelectionDto;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;
import net.explorviz.landscape.api.v3.model.landscape.AnimationFrameDto;
import net.explorviz.landscape.api.v3.model.landscape.AnimationSkeletonDto;
import net.explorviz.landscape.api.v3.model.landscape.AnimationWindowDto;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
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

  private List<CommitMeta> fetchOrderedCommits(
      final Session session, final String landscapeToken, final String repositoryName) {
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(c:Commit)
        WHERE coalesce(c.authorDate, 0) <> 0
        RETURN c.hash AS hash, c.authorDate AS authorDate
        ORDER BY coalesce(c.commitDate, c.authorDate) ASC, c.hash ASC
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
      final int granularity) {

    final int granul = Math.max(1, granularity);
    final List<CommitMeta> commits = fetchOrderedCommits(session, landscapeToken, repositoryName);

    final int commitCount = commits.size();
    if (commitCount == 0) {
      return new AnimationWindowDto(0, 0, List.of());
    }
    final int totalFrames = (commitCount + granul - 1) / granul;

    final int from = Math.max(0, start);
    if (from >= totalFrames) {
      return new AnimationWindowDto(totalFrames, totalFrames, List.of());
    }
    final int to = count < 0 ? totalFrames : Math.min(totalFrames, from + count);

    final List<AnimationFrameDto> frames = new ArrayList<>();
    for (int i = from; i < to; i++) {
      final int lastId = Math.min((i + 1) * granul, commitCount) - 1;
      final CommitMeta target = commits.get(lastId);
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
        final CommitMeta prevLast = commits.get(i * granul - 1);
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
    final Map<String, Integer> fqnToFirstOrdinal =
        computeFqnFirstOrdinals(session, landscapeToken, repositoryName, commits);

    return new AnimationSkeletonDto(landscape, fqnToFirstOrdinal, orderedCommitHashes);
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
}
