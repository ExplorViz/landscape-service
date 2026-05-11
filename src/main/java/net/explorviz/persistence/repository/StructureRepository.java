package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.persistence.api.v3.model.CommitComparison;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.TypeOfAnalysis;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto;
import net.explorviz.persistence.api.v3.model.landscape.ChimneyDto;
import net.explorviz.persistence.api.v3.model.landscape.CityDto;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel;
import net.explorviz.persistence.api.v3.model.landscape.FlatLandscapeDto;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
public class StructureRepository {

  @Inject StructureMapper mapper;

  public record StaticDataRequest(
      String landscapeToken, String repositoryName, String commitHash) {}

  public record DebugDataRequest(
      String landscapeToken, String repositoryName, String commitHash, String debugSnapshotId) {}

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

  public FlatLandscapeDto fetchFlatLandscapeForDebugData(
      final Session session, final DebugDataRequest request) {
    final String query =
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(:Repository {name: $repoName})
          -[:CONTAINS]->(:Commit {hash: $commitHash})
        MATCH (c:Commit {hash: $commitHash})<-[:RUNS_ON]-(dr:DebugRun)
        MATCH (dr)
          -[:CONTAINS]->(ds:DebugSnapShot {id: $snapshotId})
          -[:CAPTURES]->(v:Variable)
          -[:MARKED_IN]->(f:FileRevision)

        MATCH p = (a:Application)-[:HAS_ROOT]->(root:Directory)-[:CONTAINS*0..]->(f)
        WHERE (l)-[:CONTAINS]->(a)

        WITH DISTINCT a, nodes(p) AS pathNodes, v

        UNWIND [v,a] + pathNodes AS n
        WITH DISTINCT n, a, v
        RETURN
          id(n) AS id,
          labels(n) AS labels,
          properties(n) AS properties,
          id(a) AS cityId,
          [(n)-[:HAS_ROOT|CONTAINS]->(m) | id(m)]
          +
          CASE
            WHEN n:FileRevision
              THEN [(var:Variable)-[:MARKED_IN]->(n) | id(var)]
              ELSE []
          END AS childrenIds,
          CASE
              WHEN n:Variable
                THEN [(n)-[:MARKED_IN]->(fileRev:FileRevision) | id(fileRev)][0]
                ELSE [(n)<-[:HAS_ROOT|CONTAINS]-(p) | id(p)][0]
          END AS parentId
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
                request.commitHash(),
                "snapshotId",
                request.debugSnapshotId()));
    return mapper.buildFlatLandscape(request.landscapeToken(), result, TypeOfAnalysis.DEBUG, null);
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

    return merge(request.landscapeToken(), first, second);
  }

  private FlatLandscapeDto merge(
      final String token, final FlatLandscapeDto first, final FlatLandscapeDto second) {

    final Map<String, String> idMap = new HashMap<>();
    populateIdMap(first.cities(), second.cities(), idMap);
    populateIdMap(first.districts(), second.districts(), idMap);
    populateIdMap(first.buildings(), second.buildings(), idMap);

    final Map<String, CityDto> cities = new HashMap<>();
    final Map<String, DistrictDto> districts = new HashMap<>();
    final Map<String, BuildingDto> buildings = new HashMap<>();
    final Map<String, ChimneyDto> chimneys = new HashMap<>();

    mergeNodes(first.cities(), second.cities(), cities, idMap);
    mergeNodes(first.districts(), second.districts(), districts, idMap);
    mergeNodes(first.buildings(), second.buildings(), buildings, idMap);

    return new FlatLandscapeDto(token, cities, districts, buildings, chimneys);
  }

  private <T> void populateIdMap(
      final Map<String, T> firstMap,
      final Map<String, T> secondMap,
      final Map<String, String> idMap) {
    final Map<String, T> firstByFqn =
        firstMap.values().stream()
            .collect(Collectors.toMap(this::getFqn, java.util.function.Function.identity()));
    final Map<String, T> secondByFqn =
        secondMap.values().stream()
            .collect(Collectors.toMap(this::getFqn, java.util.function.Function.identity()));

    final Set<String> allFqns = new HashSet<>(firstByFqn.keySet());
    allFqns.addAll(secondByFqn.keySet());

    for (final String fqn : allFqns) {
      final T firstNode = firstByFqn.get(fqn);
      final T secondNode = secondByFqn.get(fqn);

      final String mergedId = secondNode != null ? getId(secondNode) : getId(firstNode);
      if (firstNode != null) {
        idMap.put(getId(firstNode), mergedId);
      }
      if (secondNode != null) {
        idMap.put(getId(secondNode), mergedId);
      }
    }
  }

  private <T> void mergeNodes(
      final Map<String, T> firstMap,
      final Map<String, T> secondMap,
      final Map<String, T> targetMap,
      final Map<String, String> idMap) {

    final Map<String, T> firstByFqn =
        firstMap.values().stream()
            .collect(Collectors.toMap(this::getFqn, java.util.function.Function.identity()));
    final Map<String, T> secondByFqn =
        secondMap.values().stream()
            .collect(Collectors.toMap(this::getFqn, java.util.function.Function.identity()));

    final Set<String> allFqns = new HashSet<>(firstByFqn.keySet());
    allFqns.addAll(secondByFqn.keySet());

    for (final String fqn : allFqns) {
      final T firstNode = firstByFqn.get(fqn);
      final T secondNode = secondByFqn.get(fqn);

      if (firstNode != null && secondNode != null) {
        final CommitComparison comp =
            getId(firstNode).equals(getId(secondNode))
                ? CommitComparison.UNCHANGED
                : CommitComparison.MODIFIED;
        targetMap.put(getId(secondNode), withComparison(secondNode, firstNode, comp, idMap));
      } else if (firstNode != null) {
        targetMap.put(
            getId(firstNode), withComparison(firstNode, null, CommitComparison.REMOVED, idMap));
      } else {
        targetMap.put(
            getId(secondNode), withComparison(secondNode, null, CommitComparison.ADDED, idMap));
      }
    }
  }

  private String getFqn(final Object dto) {
    if (dto instanceof CityDto d) {
      return d.flatBaseModel().name();
    }
    if (dto instanceof DistrictDto d) {
      return d.flatBaseModel().fqn();
    }
    if (dto instanceof BuildingDto d) {
      return d.flatBaseModel().fqn();
    }
    return "";
  }

  private String getId(final Object dto) {
    if (dto instanceof CityDto d) {
      return d.flatBaseModel().id();
    }
    if (dto instanceof DistrictDto d) {
      return d.flatBaseModel().id();
    }
    if (dto instanceof BuildingDto d) {
      return d.flatBaseModel().id();
    }
    return "";
  }

  @SuppressWarnings("unchecked")
  private <T> T withComparison(
      final T dto, final T otherDto, final CommitComparison comp, final Map<String, String> idMap) {
    if (dto instanceof CityDto d) {
      return (T) withComparisonCity(d, (CityDto) otherDto, comp, idMap);
    }
    if (dto instanceof DistrictDto d) {
      return (T) withComparisonDistrict(d, (DistrictDto) otherDto, comp, idMap);
    }
    if (dto instanceof BuildingDto d) {
      return (T) withComparisonBuilding(d, (BuildingDto) otherDto, comp, idMap);
    }
    return dto;
  }

  private CityDto withComparisonCity(
      final CityDto d,
      final CityDto other,
      final CommitComparison comp,
      final Map<String, String> idMap) {
    final List<String> districtIds =
        mergeLists(safeGet(d, CityDto::districtIds), safeGet(other, CityDto::districtIds), idMap);
    final List<String> buildingIds =
        mergeLists(safeGet(d, CityDto::buildingIds), safeGet(other, CityDto::buildingIds), idMap);
    final List<String> allDistricts =
        mergeLists(
            safeGet(d, CityDto::allContainedDistrictIds),
            safeGet(other, CityDto::allContainedDistrictIds),
            idMap);
    final List<String> allBuildings =
        mergeLists(
            safeGet(d, CityDto::allContainedBuildingIds),
            safeGet(other, CityDto::allContainedBuildingIds),
            idMap);
    final List<String> allChimneys = List.of();

    final FlatBaseModel base = d != null ? d.flatBaseModel() : other.flatBaseModel();
    return new CityDto(
        withBaseComparison(base, idMap.get(base.id()), comp),
        districtIds,
        buildingIds,
        allDistricts,
        allBuildings,
        allChimneys);
  }

  private DistrictDto withComparisonDistrict(
      final DistrictDto d,
      final DistrictDto other,
      final CommitComparison comp,
      final Map<String, String> idMap) {
    final List<String> districtIds =
        mergeLists(
            safeGet(d, DistrictDto::districtIds), safeGet(other, DistrictDto::districtIds), idMap);
    final List<String> buildingIds =
        mergeLists(
            safeGet(d, DistrictDto::buildingIds), safeGet(other, DistrictDto::buildingIds), idMap);

    final FlatBaseModel base = d != null ? d.flatBaseModel() : other.flatBaseModel();
    final String parentCityId = d != null ? d.parentCityId() : other.parentCityId();
    final String parentDistrictId = d != null ? d.parentDistrictId() : other.parentDistrictId();

    return new DistrictDto(
        withBaseComparison(base, idMap.get(base.id()), comp),
        idMap.get(parentCityId),
        parentDistrictId != null ? idMap.get(parentDistrictId) : null,
        districtIds,
        buildingIds);
  }

  private BuildingDto withComparisonBuilding(
      final BuildingDto d,
      final BuildingDto other,
      final CommitComparison comp,
      final Map<String, String> idMap) {
    final FlatBaseModel base = d != null ? d.flatBaseModel() : other.flatBaseModel();
    final String parentCityId = d != null ? d.parentCityId() : other.parentCityId();
    final String parentDistrictId = d != null ? d.parentDistrictId() : other.parentDistrictId();

    final Map<String, MetricValue> metrics = mergeBuildingMetrics(d, other, comp);
    final Set<String> chimneyIds =
        Set.of(); // TODO: change that to support debug snapshot comparison
    return new BuildingDto(
        withBaseComparison(base, idMap.get(base.id()), comp),
        idMap.get(parentCityId),
        parentDistrictId != null ? idMap.get(parentDistrictId) : null,
        d != null ? d.language() : other.language(),
        metrics,
        chimneyIds);
  }

  private Map<String, MetricValue> mergeBuildingMetrics(
      final BuildingDto d, final BuildingDto other, final CommitComparison comp) {
    if (d != null && other != null) {
      return mergeMetrics(d.metrics(), other.metrics());
    } else if (comp == CommitComparison.REMOVED && d != null) {
      final Map<String, MetricValue> metrics = new HashMap<>();
      d.metrics().forEach((k, v) -> metrics.put(k, new MetricValue(null, v.current())));
      return metrics;
    } else {
      return d != null ? d.metrics() : other.metrics();
    }
  }

  private FlatBaseModel withBaseComparison(
      final FlatBaseModel base, final String newId, final CommitComparison comp) {
    return new FlatBaseModel(newId, base.name(), base.fqn(), base.originOfData(), comp);
  }

  private List<String> mergeLists(
      final List<String> list1, final List<String> list2, final Map<String, String> idMap) {
    final Set<String> merged = new HashSet<>();
    if (list1 != null) {
      for (final String id : list1) {
        final String mergedId = idMap.get(id);
        if (mergedId != null) {
          merged.add(mergedId);
        }
      }
    }
    if (list2 != null) {
      for (final String id : list2) {
        final String mergedId = idMap.get(id);
        if (mergedId != null) {
          merged.add(mergedId);
        }
      }
    }
    return new ArrayList<>(merged);
  }

  private <T, R> R safeGet(final T obj, final java.util.function.Function<T, R> getter) {
    return obj == null ? null : getter.apply(obj);
  }

  private Map<String, MetricValue> mergeMetrics(
      final Map<String, MetricValue> current, final Map<String, MetricValue> previous) {
    final Map<String, MetricValue> merged = new HashMap<>();
    final Set<String> allKeys = new HashSet<>(current.keySet());
    allKeys.addAll(previous.keySet());
    for (final String k : allKeys) {
      final MetricValue curr = current.get(k);
      final MetricValue prev = previous.get(k);
      merged.put(
          k,
          new MetricValue(
              curr != null ? curr.current() : null, prev != null ? prev.current() : null));
    }
    return merged;
  }
}
