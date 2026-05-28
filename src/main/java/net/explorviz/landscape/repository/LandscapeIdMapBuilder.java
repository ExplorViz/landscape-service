package net.explorviz.landscape.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;

final class LandscapeIdMapBuilder {

  private LandscapeIdMapBuilder() {}

  static Map<String, String> build(final FlatLandscapeDto first, final FlatLandscapeDto second) {

    final Map<String, String> idMap = new HashMap<>();
    populateIdMap(first.cities(), second.cities(), idMap);
    populateIdMap(first.districts(), second.districts(), idMap);
    populateIdMap(first.buildings(), second.buildings(), idMap);
    return idMap;
  }

  private static <T> void populateIdMap(
      final Map<String, T> firstMap,
      final Map<String, T> secondMap,
      final Map<String, String> idMap) {
    registerCanonicalIds(firstMap, idMap);
    registerCanonicalIds(secondMap, idMap);

    final Map<String, T> firstByFqn = indexByFqn(firstMap.values());
    final Map<String, T> secondByFqn = indexByFqn(secondMap.values());

    final Set<String> allFqns = new HashSet<>(firstByFqn.keySet());
    allFqns.addAll(secondByFqn.keySet());

    for (final String fqn : allFqns) {
      final T firstNode = firstByFqn.get(fqn);
      final T secondNode = secondByFqn.get(fqn);

      final String mergedId =
          secondNode != null
              ? LandscapeNodeMetadata.getId(secondNode)
              : LandscapeNodeMetadata.getId(firstNode);
      if (firstNode != null) {
        idMap.put(LandscapeNodeMetadata.getId(firstNode), mergedId);
      }
      if (secondNode != null) {
        idMap.put(LandscapeNodeMetadata.getId(secondNode), mergedId);
      }
    }
  }

  private static <T> void registerCanonicalIds(
      final Map<String, T> sourceMap, final Map<String, String> idMap) {
    sourceMap.values().stream()
        .collect(Collectors.groupingBy(LandscapeNodeMetadata::getFqn))
        .values()
        .forEach(
            group -> {
              final T canonical =
                  group.stream().reduce(LandscapeSameFqnMerger::merge).orElseThrow();
              final String canonicalId = LandscapeNodeMetadata.getId(canonical);
              group.forEach(node -> idMap.put(LandscapeNodeMetadata.getId(node), canonicalId));
            });
  }

  private static <T> Map<String, T> indexByFqn(final Iterable<T> nodes) {
    return java.util.stream.StreamSupport.stream(nodes.spliterator(), false)
        .collect(
            Collectors.toMap(
                LandscapeNodeMetadata::getFqn, Function.identity(), LandscapeSameFqnMerger::merge));
  }
}
