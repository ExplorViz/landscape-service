package net.explorviz.persistence.repository;

import net.explorviz.persistence.api.v3.model.landscape.BuildingDto;
import net.explorviz.persistence.api.v3.model.landscape.CityDto;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto;

final class LandscapeSameFqnMerger {

  private LandscapeSameFqnMerger() {}

  @SuppressWarnings("unchecked")
  static <T> T merge(final T first, final T second) {
    if (first instanceof DistrictDto a && second instanceof DistrictDto b) {
      return (T) mergeDistricts(a, b);
    }
    if (first instanceof CityDto a && second instanceof CityDto b) {
      return (T) mergeCities(a, b);
    }
    if (first instanceof BuildingDto a && second instanceof BuildingDto b) {
      return (T) mergeBuildings(a, b);
    }
    return second;
  }

  private static BuildingDto mergeBuildings(final BuildingDto first, final BuildingDto second) {
    return choosePrimaryByHigherId(first, second);
  }

  private static DistrictDto mergeDistricts(final DistrictDto first, final DistrictDto second) {
    final DistrictDto primary = choosePrimary(first, second);
    final DistrictDto other = primary.equals(first) ? second : first;
    return new DistrictDto(
        primary.flatBaseModel(),
        primary.parentCityId(),
        primary.parentDistrictId(),
        LandscapeListMerger.mergeIdLists(primary.districtIds(), other.districtIds()),
        LandscapeListMerger.mergeIdLists(primary.buildingIds(), other.buildingIds()));
  }

  private static CityDto mergeCities(final CityDto first, final CityDto second) {
    final CityDto primary = choosePrimary(first, second);
    final CityDto other = primary.equals(first) ? second : first;
    return new CityDto(
        primary.flatBaseModel(),
        LandscapeListMerger.mergeIdLists(primary.districtIds(), other.districtIds()),
        LandscapeListMerger.mergeIdLists(primary.buildingIds(), other.buildingIds()),
        LandscapeListMerger.mergeIdLists(
            primary.allContainedDistrictIds(), other.allContainedDistrictIds()),
        LandscapeListMerger.mergeIdLists(
            primary.allContainedBuildingIds(), other.allContainedBuildingIds()));
  }

  private static <T> T choosePrimary(final T first, final T second) {
    return Long.parseLong(LandscapeNodeMetadata.getId(first))
            <= Long.parseLong(LandscapeNodeMetadata.getId(second))
        ? first
        : second;
  }

  private static <T> T choosePrimaryByHigherId(final T first, final T second) {
    return Long.parseLong(LandscapeNodeMetadata.getId(first))
            >= Long.parseLong(LandscapeNodeMetadata.getId(second))
        ? first
        : second;
  }
}
