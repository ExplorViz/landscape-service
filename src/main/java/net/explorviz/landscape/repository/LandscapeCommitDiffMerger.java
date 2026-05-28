package net.explorviz.landscape.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;

final class LandscapeCommitDiffMerger {

  private LandscapeCommitDiffMerger() {}

  static FlatLandscapeDto merge(
      final String token,
      final FlatLandscapeDto first,
      final FlatLandscapeDto second,
      final Map<String, String> idMap) {

    final Map<String, CityDto> cities = new HashMap<>();
    final Map<String, DistrictDto> districts = new HashMap<>();
    final Map<String, BuildingDto> buildings = new HashMap<>();

    mergeNodes(first.cities(), second.cities(), cities, idMap);
    mergeNodes(first.districts(), second.districts(), districts, idMap);
    mergeNodes(first.buildings(), second.buildings(), buildings, idMap);

    return new FlatLandscapeDto(token, cities, districts, buildings);
  }

  private static <T> void mergeNodes(
      final Map<String, T> firstMap,
      final Map<String, T> secondMap,
      final Map<String, T> targetMap,
      final Map<String, String> idMap) {

    final Map<String, T> firstByFqn = indexByFqn(firstMap.values());
    final Map<String, T> secondByFqn = indexByFqn(secondMap.values());

    final Set<String> allFqns = new HashSet<>(firstByFqn.keySet());
    allFqns.addAll(secondByFqn.keySet());

    for (final String fqn : allFqns) {
      mergeNodePair(firstByFqn.get(fqn), secondByFqn.get(fqn), targetMap, idMap);
    }
  }

  private static <T> Map<String, T> indexByFqn(final Iterable<T> nodes) {
    return java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
        .collect(
            Collectors.toMap(
                LandscapeNodeMetadata::getFqn, Function.identity(), LandscapeSameFqnMerger::merge));
  }

  private static <T> void mergeNodePair(
      final T firstNode,
      final T secondNode,
      final Map<String, T> targetMap,
      final Map<String, String> idMap) {
    if (firstNode != null && secondNode != null) {
      final CommitComparison comp =
          LandscapeNodeMetadata.getId(firstNode).equals(LandscapeNodeMetadata.getId(secondNode))
              ? CommitComparison.UNCHANGED
              : CommitComparison.MODIFIED;
      targetMap.put(
          LandscapeNodeMetadata.getId(secondNode),
          withComparison(secondNode, firstNode, comp, idMap));
      return;
    }
    if (firstNode != null) {
      targetMap.put(
          LandscapeNodeMetadata.getId(firstNode),
          withComparison(firstNode, null, CommitComparison.REMOVED, idMap));
      return;
    }
    targetMap.put(
        LandscapeNodeMetadata.getId(secondNode),
        withComparison(secondNode, null, CommitComparison.ADDED, idMap));
  }

  @SuppressWarnings("unchecked")
  private static <T> T withComparison(
      final T dto, final T otherDto, final CommitComparison comp, final Map<String, String> idMap) {
    if (dto instanceof CityDto d) {
      return (T) CityCommitDiffApplier.apply(d, (CityDto) otherDto, comp, idMap);
    }
    if (dto instanceof DistrictDto d) {
      return (T) DistrictCommitDiffApplier.apply(d, (DistrictDto) otherDto, comp, idMap);
    }
    if (dto instanceof BuildingDto d) {
      return (T) BuildingCommitDiffApplier.apply(d, (BuildingDto) otherDto, comp, idMap);
    }
    return dto;
  }
}
