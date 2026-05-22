package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.persistence.api.v3.model.RepositoryEvolutionSelectionDto;
import net.explorviz.persistence.api.v3.model.TypeOfAnalysis;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto;
import net.explorviz.persistence.api.v3.model.landscape.CityDto;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto;
import net.explorviz.persistence.api.v3.model.landscape.FlatLandscapeDto;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class StructureRepository {

  private static final FlatLandscapeMerger LANDSCAPE_MERGER = new FlatLandscapeMerger();

  @Inject StructureMapper mapper;

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
}
