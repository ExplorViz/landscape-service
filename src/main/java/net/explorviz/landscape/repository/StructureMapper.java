package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.MetricValue;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.api.v3.model.landscape.ModelType;
import org.neo4j.ogm.model.Result;

/** Static mapper class for converting Neo4j results into FlatLandscapeDto. */
public final class StructureMapper {

  private static final String LABEL_APPLICATION = "Application";
  private static final String LABEL_SCOPE = "Scope";

  private static final String LABEL_DIRECTORY = "Directory";
  private static final String LABEL_FILE_REVISION = "FileRevision";

  private static final String LABEL_RPC_SYSTEM = "RPCSystem";
  private static final String LABEL_RPC_NAMESPACE = "RPCNamespace";
  private static final String LABEL_RPC_SERVICE = "RPCService";
  private static final String LABEL_HTTP_ENDPOINT = "HTTPEndpoint";
  private static final String LABEL_GENERIC_ENTITY = "GenericTelemetryEntity";

  private static final Set<String> CITY_LABELS = Set.of("Application");

  private static final Set<String> DISTRICT_LABELS =
      Set.of(LABEL_SCOPE, LABEL_DIRECTORY, LABEL_RPC_SYSTEM, LABEL_RPC_NAMESPACE);

  private static final Set<String> BUILDING_LABELS =
      Set.of(LABEL_FILE_REVISION, LABEL_RPC_SERVICE, LABEL_HTTP_ENDPOINT, LABEL_GENERIC_ENTITY);

  private static final Map<String, ModelType> LABEL_TO_TYPE =
      Map.of(
          LABEL_APPLICATION, ModelType.SERVICE,
          LABEL_SCOPE, ModelType.INSTRUMENTATION_SCOPE,
          LABEL_DIRECTORY, ModelType.CODE,
          LABEL_FILE_REVISION, ModelType.CODE,
          LABEL_RPC_SYSTEM, ModelType.RPC,
          LABEL_RPC_NAMESPACE, ModelType.RPC,
          LABEL_RPC_SERVICE, ModelType.RPC,
          LABEL_HTTP_ENDPOINT, ModelType.HTTP,
          LABEL_GENERIC_ENTITY, ModelType.UNKNOWN);

  public record NodeData(
      Long id,
      Set<String> labels,
      Map<String, Object> properties,
      String name,
      String fqn,
      Long cityId,
      List<Long> childrenIds,
      Long parentId) {

    private static NodeData fromRow(final Map<String, Object> row) {
      final Long id = (Long) row.get("id");
      final Set<String> labels = Set.of((String[]) row.get("labels"));

      @SuppressWarnings("unchecked")
      final Map<String, Object> properties = (Map<String, Object>) row.get("properties");

      final String name = (String) row.get("name");
      final String fqn = (String) row.get("fqn");
      final Long cityId = (Long) row.get("cityId");

      final List<Long> childrenIds =
          row.get("childrenIds") instanceof Long[] arr ? List.of(arr) : List.of();

      final Long parentId = (Long) row.get("parentId");

      return new NodeData(id, labels, properties, name, fqn, cityId, childrenIds, parentId);
    }
  }

  private record ChildIds(List<String> districts, List<String> buildings) {}

  private StructureMapper() {}

  static FlatLandscapeDto buildFlatLandscape(
      final String landscapeToken, final Result queryResult, final TypeOfAnalysis origin) {

    final Map<Long, NodeData> nodesById = new HashMap<>();

    queryResult.forEach(
        row -> {
          final NodeData data = NodeData.fromRow(row);
          nodesById.put(data.id, data);
        });

    final Map<String, List<String>> appIdToAllDistrictIds = new HashMap<>();
    final Map<String, List<String>> appIdToAllBuildingIds = new HashMap<>();

    final FlatLandscapeDto landscape = FlatLandscapeDto.newEmptyLandscape(landscapeToken);

    for (final NodeData data : nodesById.values()) {
      final String id = String.valueOf(data.id);
      final String cityId = String.valueOf(data.cityId);
      final String name = data.name;
      final String fqn = data.fqn;
      final String telemetryKey = (String) data.properties.get("telemetryKey");
      final ModelType type = getModelType(data);

      final FlatBaseModel model =
          new FlatBaseModel(id, name, fqn, telemetryKey, type, origin, null);

      final ChildIds childIds = getChildIds(nodesById, data);

      final NodeData parent = nodesById.get(data.parentId);
      final String parentDistrictId =
          parent != null && isDistrict(parent) ? String.valueOf(parent.id) : null;

      if (isCity(data)) {
        final List<String> allDistrictIds =
            appIdToAllDistrictIds.computeIfAbsent(id, k -> new ArrayList<>());
        final List<String> allBuildingIds =
            appIdToAllBuildingIds.computeIfAbsent(id, k -> new ArrayList<>());
        landscape
            .cities()
            .put(
                id,
                new CityDto(
                    model, childIds.districts, childIds.buildings, allDistrictIds, allBuildingIds));
      } else if (isDistrict(data)) {
        landscape
            .districts()
            .put(
                id,
                new DistrictDto(
                    model, cityId, parentDistrictId, childIds.districts, childIds.buildings));
        appIdToAllDistrictIds.computeIfAbsent(cityId, k -> new ArrayList<>()).add(id);
      } else if (isBuilding(data)) {
        landscape
            .buildings()
            .put(
                id,
                new BuildingDto(
                    model,
                    cityId,
                    parentDistrictId,
                    (String) data.properties.get("language"),
                    extractMetrics(data.properties),
                    (String) data.properties.get("hash")));
        appIdToAllBuildingIds.computeIfAbsent(cityId, k -> new ArrayList<>()).add(id);
      }
    }

    return landscape;
  }

  private static ChildIds getChildIds(final Map<Long, NodeData> nodesById, final NodeData data) {
    final ChildIds childIds = new ChildIds(new ArrayList<>(), new ArrayList<>());
    for (final Long childId : data.childrenIds) {
      final NodeData child = nodesById.get(childId);
      final String childIdStr = String.valueOf(childId);

      if (isDistrict(child)) {
        childIds.districts.add(childIdStr);
      } else if (isBuilding(child)) {
        childIds.buildings.add(childIdStr);
      } else {
        Log.warnf("Ignoring unhandled child node type: %s", child);
      }
    }
    return childIds;
  }

  private static boolean isCity(final NodeData node) {
    for (final String label : node.labels) {
      if (CITY_LABELS.contains(label)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isDistrict(final NodeData node) {
    for (final String label : node.labels) {
      if (DISTRICT_LABELS.contains(label)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isBuilding(final NodeData node) {
    for (final String label : node.labels) {
      if (BUILDING_LABELS.contains(label)) {
        return true;
      }
    }
    return false;
  }

  private static ModelType getModelType(final NodeData node) {
    for (final String label : node.labels) {
      final ModelType type = LABEL_TO_TYPE.get(label);
      if (type != null) {
        return type;
      }
    }
    Log.warnf("Encountered unmapped node labels: %s", node.labels);
    return ModelType.UNKNOWN;
  }

  private static Map<String, MetricValue> extractMetrics(final Map<String, Object> properties) {
    final String metricsPropertyPrefix = "metrics.";
    final Map<String, Double> metrics = new HashMap<>();
    properties.forEach(
        (k, v) -> {
          if (k.startsWith(metricsPropertyPrefix) && v instanceof Number n) {
            metrics.put(k.substring(metricsPropertyPrefix.length()), n.doubleValue());
          }
        });
    return MetricValue.fromMap(metrics);
  }
}
