package net.explorviz.persistence.repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LandscapeListMerger {

  private LandscapeListMerger() {}

  static List<String> mergeIdLists(final List<String> first, final List<String> second) {
    final Set<String> merged = new HashSet<>();
    merged.addAll(first);
    merged.addAll(second);
    return new ArrayList<>(merged);
  }

  static List<String> mergeMappedIdLists(
      final List<String> list1, final List<String> list2, final Map<String, String> idMap) {
    final Set<String> merged = new HashSet<>();
    addMappedIds(list1, idMap, merged);
    addMappedIds(list2, idMap, merged);
    return new ArrayList<>(merged);
  }

  private static void addMappedIds(
      final List<String> ids, final Map<String, String> idMap, final Set<String> merged) {
    if (ids == null) {
      return;
    }
    for (final String id : ids) {
      final String mergedId = idMap.get(id);
      if (mergedId != null) {
        merged.add(mergedId);
      }
    }
  }
}
