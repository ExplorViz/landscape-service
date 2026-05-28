package net.explorviz.landscape.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel;

final class DistrictCommitDiffApplier {

  private DistrictCommitDiffApplier() {}

  static DistrictDto apply(
      final DistrictDto current,
      final DistrictDto other,
      final CommitComparison comp,
      final Map<String, String> idMap) {
    final List<String> districtIds =
        LandscapeListMerger.mergeMappedIdLists(
            safeGet(current, DistrictDto::districtIds),
            safeGet(other, DistrictDto::districtIds),
            idMap);
    final List<String> buildingIds =
        LandscapeListMerger.mergeMappedIdLists(
            safeGet(current, DistrictDto::buildingIds),
            safeGet(other, DistrictDto::buildingIds),
            idMap);

    final FlatBaseModel base = current != null ? current.flatBaseModel() : other.flatBaseModel();
    final String parentCityId = current != null ? current.parentCityId() : other.parentCityId();
    final String parentDistrictId =
        current != null ? current.parentDistrictId() : other.parentDistrictId();

    return new DistrictDto(
        LandscapeBaseModelDiffApplier.apply(base, idMap.get(base.id()), comp),
        idMap.get(parentCityId),
        parentDistrictId != null ? idMap.get(parentDistrictId) : null,
        districtIds,
        buildingIds);
  }

  private static <T, R> R safeGet(final T obj, final Function<T, R> getter) {
    return obj == null ? null : getter.apply(obj);
  }
}
