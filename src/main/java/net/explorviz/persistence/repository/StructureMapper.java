package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.TypeOfAnalysis;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto;
import net.explorviz.persistence.api.v3.model.landscape.ChimneyDto;
import net.explorviz.persistence.api.v3.model.landscape.ChimneyPlatformDto;
import net.explorviz.persistence.api.v3.model.landscape.CityDto;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel;
import net.explorviz.persistence.api.v3.model.landscape.FlatLandscapeDto;
import org.jboss.logging.Logger;
import org.neo4j.ogm.model.Result;

/** Mapper class for converting Neo4j results into FlatLandscapeDto. */
@ApplicationScoped
public class StructureMapper {

  private static final Logger LOGGER = Logger.getLogger(StructureMapper.class);

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
          if (nodeData.labels.contains("Application")) {
            applicationIds.add(nodeData.id);
          }
        });

    final TraversalContext context =
        new TraversalContext(
            nodesById,
            new HashMap<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashMap<>(),
            new HashMap<>(),
            origin);

    for (final Long appId : applicationIds) {
      final NodeData appNode = nodesById.get(appId);
      traverse(appNode, repositoryName != null ? repositoryName : "", null, context);
    }

    return new FlatLandscapeDto(
        landscapeToken,
        context.cities(),
        context.districts(),
        context.buildings(),
        context.chimneyPlatforms(),
        context.chimneys());
  }

  private record TraversalContext(
      Map<Long, NodeData> nodesById,
      Map<String, CityDto> cities,
      Map<String, DistrictDto> districts,
      Map<String, BuildingDto> buildings,
      Map<String, ChimneyPlatformDto> chimneyPlatforms,
      Map<String, ChimneyDto> chimneys,
      TypeOfAnalysis origin) {}

  private TraversalResult traverse(
      final NodeData node,
      final String parentFqn,
      final String parentCityId,
      final TraversalContext context) {

    final String name = (String) node.properties.get("name");
    final String fqn = parentFqn.isEmpty() ? name : parentFqn + "/" + name;
    final String id = String.valueOf(node.id);

    final Set<String> containedDistrictIds = new HashSet<>();
    final Set<String> containedBuildingIds = new HashSet<>();
    final Set<String> containedChimneyPlatformIds = new HashSet<>();
    final Set<String> containedChimneyIds = new HashSet<>();

    final FlatBaseModel base = new FlatBaseModel(id, name, fqn, context.origin(), null, null);

    if (node.labels.contains("Application")) {
      handleApplication(
          node,
          id,
          name,
          context,
          containedDistrictIds,
          containedBuildingIds,
          containedChimneyPlatformIds,
          containedChimneyIds);
    } else if (node.labels.contains("Directory")) {
      handleDirectory(
          node,
          id,
          fqn,
          parentCityId,
          base,
          context,
          containedDistrictIds,
          containedBuildingIds,
          containedChimneyPlatformIds,
          containedChimneyIds);
    } else if (node.labels.contains("FileRevision")) {
      handleFileRevision(
          node,
          id,
          fqn,
          parentCityId,
          base,
          context,
          containedBuildingIds,
          containedChimneyPlatformIds,
          containedChimneyIds);
    } else if (node.labels.contains("Variable")) {
      handleVariable(node, id, parentCityId, base, context, containedChimneyIds);
    }

    return new TraversalResult(
        containedDistrictIds,
        containedBuildingIds,
        containedChimneyPlatformIds,
        containedChimneyIds);
  }

  private void handleApplication(
      final NodeData node,
      final String id,
      final String name,
      final TraversalContext context,
      final Set<String> containedDistrictIds,
      final Set<String> containedBuildingIds,
      final Set<String> containedChimneyPlatformIds,
      final Set<String> containedChimneyIds) {
    final List<String> directDistrictIds = new ArrayList<>();
    final List<String> directBuildingIds = new ArrayList<>();

    for (final Long childId : node.childrenIds) {
      final NodeData child = context.nodesById().get(childId);
      if (child != null) {
        if (child.labels.contains("Directory")) {
          directDistrictIds.add(String.valueOf(child.id));
        } else if (child.labels.contains("FileRevision")) {
          directBuildingIds.add(String.valueOf(child.id));
        }
        final TraversalResult res = traverse(child, "", id, context);
        containedDistrictIds.addAll(res.districtIds);
        containedBuildingIds.addAll(res.buildingIds);
        containedChimneyPlatformIds.addAll(res.chimneyPlatformIds);
        containedChimneyIds.addAll(res.chimneyIds);
      }
    }

    final CityDto city =
        new CityDto(
            new FlatBaseModel(id, name, "", context.origin(), null, null),
            directDistrictIds,
            directBuildingIds,
            new ArrayList<>(containedDistrictIds),
            new ArrayList<>(containedBuildingIds),
            new ArrayList<>(containedChimneyPlatformIds),
            new ArrayList<>(containedChimneyIds));
    context.cities().put(id, city);
  }

  @SuppressWarnings("PMD.ExcessiveParameterList")
  private void handleDirectory(
      final NodeData node,
      final String id,
      final String fqn,
      final String parentCityId,
      final FlatBaseModel base,
      final TraversalContext context,
      final Set<String> containedDistrictIds,
      final Set<String> containedBuildingIds,
      final Set<String> containedChimneyPlatformIds,
      final Set<String> containedChimneyIds) {
    final List<String> childDistrictIds = new ArrayList<>();
    final List<String> childBuildingIds = new ArrayList<>();

    containedDistrictIds.add(id);

    for (final Long childId : node.childrenIds) {
      final NodeData child = context.nodesById().get(childId);
      if (child != null) {
        if (child.labels.contains("Directory")) {
          childDistrictIds.add(String.valueOf(child.id));
        } else if (child.labels.contains("FileRevision")) {
          childBuildingIds.add(String.valueOf(child.id));
        }
        final TraversalResult res = traverse(child, fqn, parentCityId, context);
        containedDistrictIds.addAll(res.districtIds);
        containedBuildingIds.addAll(res.buildingIds);
        containedChimneyPlatformIds.addAll(res.chimneyPlatformIds);
        containedChimneyIds.addAll(res.chimneyIds);
      }
    }

    final DistrictDto district =
        new DistrictDto(
            base, parentCityId, String.valueOf(node.parentId), childDistrictIds, childBuildingIds);
    context.districts().put(id, district);
  }

  private void handleFileRevision(
      final NodeData node,
      final String id,
      final String fqn,
      final String parentCityId,
      final FlatBaseModel base,
      final TraversalContext context,
      final Set<String> containedBuildingIds,
      final Set<String> containedChimneyPlatformIds,
      final Set<String> containedChimneyIds) {
    containedBuildingIds.add(id);

    final Set<String> instanceIds = new HashSet<>();
    final List<String> childChimneyPlatformIds = new ArrayList<>();

    final Map<String, List<String>> chimneyIdsByChimneyPlatformId = new HashMap<>();

    for (final Long childId : node.childrenIds) {
      final NodeData child = context.nodesById().get(childId);
      if (child == null) {
        continue;
      }

      if (child.labels.contains("Variable")) {
        final String instanceId = (String) child.properties.get("instanceId");
        chimneyIdsByChimneyPlatformId
            .computeIfAbsent(instanceId, ignored -> new ArrayList<>())
            .add(String.valueOf(child.id));
        instanceIds.add(instanceId);

        final TraversalResult res =
            traverse(child, appendInstanceId(fqn, instanceId), parentCityId, context);
        containedChimneyIds.addAll(res.chimneyIds); // child and contained chimney ids are the same
      }
    }

    childChimneyPlatformIds.addAll(instanceIds);
    // Since chimney platforms are only handled here, the child and contained ids are the same
    containedChimneyPlatformIds.addAll(instanceIds);

    final BuildingDto building =
        new BuildingDto(
            base,
            parentCityId,
            String.valueOf(node.parentId),
            (String) node.properties.get("language"),
            extractMetrics(node.properties),
            childChimneyPlatformIds);
    context.buildings().put(id, building);

    instanceIds.forEach(
        instanceId -> {
          final FlatBaseModel chimneyPlatformModel =
              new FlatBaseModel(
                  instanceId,
                  appendInstanceId(base.name(), instanceId),
                  appendInstanceId(base.fqn(), instanceId),
                  base.originOfData(),
                  base.commitComparison(),
                  base.debugSnapshotComparison());
          final List<String> chimneyIds =
              chimneyIdsByChimneyPlatformId.getOrDefault(instanceId, List.of());
          final ChimneyPlatformDto chimneyPlatform =
              new ChimneyPlatformDto(
                  chimneyPlatformModel, parentCityId, instanceId, id, chimneyIds);
          context.chimneyPlatforms().put(instanceId, chimneyPlatform);
        });
  }

  private String appendInstanceId(final String base, final String instanceId) {
    return base + "#" + instanceId;
  }

  private void handleVariable(
      final NodeData node,
      final String id,
      final String parentCityId,
      final FlatBaseModel base,
      final TraversalContext context,
      final Set<String> containedChimneyIds) {
    containedChimneyIds.add(id);

    final ChimneyDto chimney =
        new ChimneyDto(
            base,
            parentCityId,
            (String) node.properties.get("instanceId"),
            (String) node.properties.get("value"),
            (String) node.properties.get("type"),
            extractMetrics(node.properties));
    context.chimneys().put(id, chimney);
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

  private record TraversalResult(
      Set<String> districtIds,
      Set<String> buildingIds,
      Set<String> chimneyPlatformIds,
      Set<String> chimneyIds) {}
}
