package net.explorviz.persistence.repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.explorviz.persistence.api.v3.model.CommitComparison;
import net.explorviz.persistence.api.v3.model.landscape.CityDto;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel;

final class CityCommitDiffApplier {

  private CityCommitDiffApplier() {}

  static CityDto apply(
      final CityDto current,
      final CityDto other,
      final CommitComparison comp,
      final Map<String, String> idMap) {
    final List<String> districtIds =
        LandscapeListMerger.mergeMappedIdLists(
            safeGet(current, CityDto::districtIds), safeGet(other, CityDto::districtIds), idMap);
    final List<String> buildingIds =
        LandscapeListMerger.mergeMappedIdLists(
            safeGet(current, CityDto::buildingIds), safeGet(other, CityDto::buildingIds), idMap);
    final List<String> allDistricts =
        LandscapeListMerger.mergeMappedIdLists(
            safeGet(current, CityDto::allContainedDistrictIds),
            safeGet(other, CityDto::allContainedDistrictIds),
            idMap);
    final List<String> allBuildings =
        LandscapeListMerger.mergeMappedIdLists(
            safeGet(current, CityDto::allContainedBuildingIds),
            safeGet(other, CityDto::allContainedBuildingIds),
            idMap);

    final FlatBaseModel base = current != null ? current.flatBaseModel() : other.flatBaseModel();
    return new CityDto(
        LandscapeBaseModelDiffApplier.apply(base, idMap.get(base.id()), comp),
        districtIds,
        buildingIds,
        allDistricts,
        allBuildings);
  }

  private static <T, R> R safeGet(final T obj, final Function<T, R> getter) {
    return obj == null ? null : getter.apply(obj);
  }
}
