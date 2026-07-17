package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
import org.neo4j.ogm.model.Result;

/** Mapper class for converting Neo4j results into FlatLandscapeDto. */
@ApplicationScoped
public class StructureMapper {

  private static final String LABEL_APPLICATION = "Application";
  private static final String LABEL_DIRECTORY = "Directory";
  private static final String LABEL_FILE_REVISION = "FileRevision";

  public FlatLandscapeDto buildFlatLandscape(
      final String landscapeToken,
      final Result result,
      final TypeOfAnalysis origin,
      final String repositoryName) {
    final Map<Long, NodeData> nodesById = new HashMap<>();
    final Set<Long> applicationIds = new HashSet<>();

    result.forEach(
        row -> {
          final NodeData nodeData = parseNodeData(row);
          nodesById.put(nodeData.id, nodeData);
          if (nodeData.labels.contains(LABEL_APPLICATION)) {
            applicationIds.add(nodeData.id);
          }
        });

    final TraversalContext context =
        new TraversalContext(nodesById, new HashMap<>(), new HashMap<>(), new HashMap<>(), origin);

    for (final Long appId : applicationIds) {
      final NodeData appNode = nodesById.get(appId);
      traverse(appNode, repositoryName != null ? repositoryName : "", null, null, context);
    }

    return new FlatLandscapeDto(
        landscapeToken, context.cities(), context.districts(), context.buildings());
  }

  private record TraversalContext(
      Map<Long, NodeData> nodesById,
      Map<String, CityDto> cities,
      Map<String, DistrictDto> districts,
      Map<String, BuildingDto> buildings,
      TypeOfAnalysis origin) {}

  private TraversalResult traverse(
      final NodeData node,
      final String parentFqn,
      final String parentCityId,
      final String parentDistrictId,
      final TraversalContext context) {

    final String name = (String) node.properties.get("name");
    final String fqn = parentFqn.isEmpty() ? name : parentFqn + "/" + name;
    final String id = String.valueOf(node.id);
    final String telemetryKey = (String) node.properties.get("telemetryKey");

    final Set<String> containedDistrictIds = new HashSet<>();
    final Set<String> containedBuildingIds = new HashSet<>();

    final FlatBaseModel base =
        new FlatBaseModel(id, name, fqn, telemetryKey, context.origin(), null);

    if (node.labels.contains(LABEL_APPLICATION)) {
      handleApplication(node, id, name, context, containedDistrictIds, containedBuildingIds);
    } else if (isDirectory(node)) {
      handleDirectory(
          node,
          id,
          fqn,
          parentCityId,
          parentDistrictId,
          base,
          context,
          containedDistrictIds,
          containedBuildingIds);
    } else if (isFileRevision(node)) {
      handleFileRevision(
          node, id, parentCityId, parentDistrictId, base, context, containedBuildingIds);
    }

    return new TraversalResult(containedDistrictIds, containedBuildingIds);
  }

  private void handleApplication(
      final NodeData node,
      final String id,
      final String name,
      final TraversalContext context,
      final Set<String> containedDistrictIds,
      final Set<String> containedBuildingIds) {
    final List<String> directDistrictIds = new ArrayList<>();
    final List<String> directBuildingIds = new ArrayList<>();

    for (final Long childId : node.childrenIds) {
      collectDirectCityContentFromChild(
          context.nodesById().get(childId),
          id,
          context,
          directDistrictIds,
          directBuildingIds,
          containedDistrictIds,
          containedBuildingIds);
    }

    final CityDto city =
        new CityDto(
            new FlatBaseModel(id, name, "", null, context.origin(), null),
            directDistrictIds,
            directBuildingIds,
            new ArrayList<>(containedDistrictIds),
            new ArrayList<>(containedBuildingIds));
    context.cities().put(id, city);
  }

  private void collectDirectCityContentFromChild(
      final NodeData child,
      final String cityId,
      final TraversalContext context,
      final List<String> directDistrictIds,
      final List<String> directBuildingIds,
      final Set<String> containedDistrictIds,
      final Set<String> containedBuildingIds) {
    if (child == null) {
      return;
    }
    if (isDirectory(child)) {
      // Application root directory is not exposed as a district; its children belong to the city.
      for (final Long grandChildId : child.childrenIds) {
        final NodeData grandChild = context.nodesById().get(grandChildId);
        if (grandChild == null) {
          continue;
        }
        registerDirectCityChild(grandChild, directDistrictIds, directBuildingIds);
        applyTraversalResult(
            traverse(grandChild, "", cityId, null, context),
            containedDistrictIds,
            containedBuildingIds);
      }
    } else if (isFileRevision(child)) {
      registerDirectCityChild(child, directDistrictIds, directBuildingIds);
      applyTraversalResult(
          traverse(child, "", cityId, null, context), containedDistrictIds, containedBuildingIds);
    }
  }

  private void registerDirectCityChild(
      final NodeData node,
      final List<String> directDistrictIds,
      final List<String> directBuildingIds) {
    if (isDirectory(node)) {
      directDistrictIds.add(String.valueOf(node.id));
    } else if (isFileRevision(node)) {
      directBuildingIds.add(String.valueOf(node.id));
    }
  }

  private void applyTraversalResult(
      final TraversalResult result,
      final Set<String> containedDistrictIds,
      final Set<String> containedBuildingIds) {
    containedDistrictIds.addAll(result.districtIds);
    containedBuildingIds.addAll(result.buildingIds);
  }

  private boolean isDirectory(final NodeData node) {
    return node.labels.contains(LABEL_DIRECTORY);
  }

  private boolean isFileRevision(final NodeData node) {
    return node.labels.contains(LABEL_FILE_REVISION);
  }

  private void handleDirectory(
      final NodeData node,
      final String id,
      final String fqn,
      final String parentCityId,
      final String parentDistrictId,
      final FlatBaseModel base,
      final TraversalContext context,
      final Set<String> containedDistrictIds,
      final Set<String> containedBuildingIds) {
    final List<String> childDistrictIds = new ArrayList<>();
    final List<String> childBuildingIds = new ArrayList<>();

    containedDistrictIds.add(id);

    for (final Long childId : node.childrenIds) {
      final NodeData child = context.nodesById().get(childId);
      if (child != null) {
        if (isDirectory(child)) {
          childDistrictIds.add(String.valueOf(child.id));
        } else if (isFileRevision(child)) {
          childBuildingIds.add(String.valueOf(child.id));
        }
        final TraversalResult res = traverse(child, fqn, parentCityId, id, context);
        containedDistrictIds.addAll(res.districtIds);
        containedBuildingIds.addAll(res.buildingIds);
      }
    }

    final DistrictDto district =
        new DistrictDto(base, parentCityId, parentDistrictId, childDistrictIds, childBuildingIds);
    context.districts().put(id, district);
  }

  private void handleFileRevision(
      final NodeData node,
      final String id,
      final String parentCityId,
      final String parentDistrictId,
      final FlatBaseModel base,
      final TraversalContext context,
      final Set<String> containedBuildingIds) {
    containedBuildingIds.add(id);

    final BuildingDto building =
        new BuildingDto(
            base,
            parentCityId,
            parentDistrictId,
            (String) node.properties.get("language"),
            extractMetrics(node.properties),
            (String) node.properties.get("hash"));
    context.buildings().put(id, building);
  }

  private NodeData parseNodeData(final Map<String, Object> row) {
    final Long id = (Long) row.get("id");

    final Object labelsObj = row.get("labels");
    final Set<String> labels = new HashSet<>();
    if (labelsObj instanceof String[] arr) {
      labels.addAll(Arrays.asList(arr));
    } else if (labelsObj instanceof Collection<?> coll) {
      coll.forEach(l -> labels.add((String) l));
    }

    @SuppressWarnings("unchecked")
    final Map<String, Object> properties = (Map<String, Object>) row.get("properties");
    final Long cityId = (Long) row.get("cityId");

    final Object childrenIdsObj = row.get("childrenIds");
    final List<Long> childrenIds = new ArrayList<>();
    if (childrenIdsObj instanceof long[] arr) {
      for (final long l : arr) {
        childrenIds.add(l);
      }
    } else if (childrenIdsObj instanceof Long[] arr) {
      childrenIds.addAll(Arrays.asList(arr));
    } else if (childrenIdsObj instanceof Collection<?> coll) {
      coll.forEach(c -> childrenIds.add((Long) c));
    }

    final Long parentId = (Long) row.get("parentId");

    return new NodeData(id, labels, properties, cityId, childrenIds, parentId);
  }

  private Map<String, MetricValue> extractMetrics(final Map<String, Object> properties) {
    final Map<String, Double> metrics = new HashMap<>();
    properties.forEach(
        (k, v) -> {
          if (k.startsWith("metrics.") && v instanceof Number n) {
            metrics.put(k.substring(8), n.doubleValue());
          }
        });
    return MetricValue.fromMap(metrics);
  }

  private static class NodeData {
    final Long id;
    final Set<String> labels;
    final Map<String, Object> properties;
    final Long cityId;
    final List<Long> childrenIds;
    final Long parentId;

    NodeData(
        final Long id,
        final Set<String> labels,
        final Map<String, Object> properties,
        final Long cityId,
        final List<Long> childrenIds,
        final Long parentId) {
      this.id = id;
      this.labels = labels;
      this.properties = properties;
      this.cityId = cityId;
      this.childrenIds = childrenIds;
      this.parentId = parentId;
    }
  }

  private record TraversalResult(Set<String> districtIds, Set<String> buildingIds) {}
}
