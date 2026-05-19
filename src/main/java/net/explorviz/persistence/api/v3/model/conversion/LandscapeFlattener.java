package net.explorviz.persistence.api.v3.model.conversion;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto.BuildingConvertible;
import net.explorviz.persistence.api.v3.model.landscape.CityDto;
import net.explorviz.persistence.api.v3.model.landscape.CityDto.CityConvertible;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto.DistrictConvertible;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel;
import net.explorviz.persistence.api.v3.model.landscape.FlatLandscapeDto;

/**
 * Walks a nested city structure as given by {@link CityConvertible} and produces a flattened
 * landscape {@link FlatLandscapeDto} from it.
 */
public final class LandscapeFlattener {

  private LandscapeFlattener() {}

  /**
   * Provides access to parent IDs during traversal of the unflattened structure and collects the
   * result of the flattening in shared sets to avoid having to merge partial results. A context is
   * created for each City. Each traversed object produces a modified version of the context to pass
   * down to its children.
   */
  private record Context(
      String parentId,
      String parentCityId,
      String parentDistrictId,
      String parentFqn,
      Set<DistrictDto> districts,
      Set<BuildingDto> buildings) {

    private Context withParent(final DistrictDto district) {
      districts.add(district);
      return new Context(
          district.flatBaseModel().id(),
          parentCityId,
          district.flatBaseModel().id(),
          district.flatBaseModel().fqn(),
          districts,
          buildings);
    }

    private Context withParent(final BuildingDto building) {
      buildings.add(building);
      return new Context(
          building.flatBaseModel().id(),
          parentCityId,
          parentDistrictId,
          building.flatBaseModel().fqn(),
          districts,
          buildings);
    }
  }

  /** Aggregates all landscape models across multiple cities to produce the final result lists. */
  private record FlatteningResult(
      Set<CityDto> cities, Set<DistrictDto> districts, Set<BuildingDto> buildings) {}

  /**
   * Produces a flat landscape with the given landscape token ID from a collection of
   * city-interpretable objects (see {@link CityConvertible}).
   *
   * @param landscapeToken String identifier to give to the produced landscape.
   * @param cityConvertibles Collection of city-interpretable objects to flatten and include in the
   *     created landscape
   * @return The flattened landscape (see {@link FlatLandscapeDto}) containing the given cities.
   */
  public static FlatLandscapeDto flattenLandscape(
      final String landscapeToken, final Collection<CityConvertible> cityConvertibles) {

    final FlatteningResult resultContainer =
        new FlatteningResult(new HashSet<>(), new HashSet<>(), new HashSet<>());

    cityConvertibles.forEach(c -> flattenCity(c, resultContainer));

    final Map<String, CityDto> cities =
        resultContainer.cities.stream()
            .collect(
                Collectors.toMap(c -> c.flatBaseModel().id(), Function.identity(), (c1, c2) -> c1));

    final Map<String, DistrictDto> districts =
        resultContainer.districts.stream()
            .collect(
                Collectors.toMap(d -> d.flatBaseModel().id(), Function.identity(), (d1, d2) -> d1));

    final Map<String, BuildingDto> buildings =
        resultContainer.buildings.stream()
            .collect(
                Collectors.toMap(b -> b.flatBaseModel().id(), Function.identity(), (b1, b2) -> b1));

    return new FlatLandscapeDto(landscapeToken, cities, districts, buildings);
  }

  private static void flattenCity(
      final CityConvertible cityConvertible, final FlatteningResult resultContainer) {

    final Collection<? extends DistrictConvertible> districts = cityConvertible.getDistricts();
    final Collection<? extends BuildingConvertible> buildings = cityConvertible.getBuildings();

    final Context context =
        new Context(
            cityConvertible.getId(),
            cityConvertible.getId(),
            null,
            "",
            new HashSet<>(),
            new HashSet<>());

    districts.forEach(d -> flattenDistrict(d, context));
    buildings.forEach(b -> flattenBuilding(b, context));

    final CityDto city =
        new CityDto(
            new FlatBaseModel(
                cityConvertible.getId(),
                cityConvertible.getName(),
                "",
                cityConvertible.getOriginOfData(),
                cityConvertible.getCommitComparison()),
            districts.stream().map(DistrictConvertible::getId).toList(),
            buildings.stream().map(BuildingConvertible::getId).toList(),
            context.districts.stream().map(d -> d.flatBaseModel().id()).toList(),
            context.buildings.stream().map(b -> b.flatBaseModel().id()).toList());

    resultContainer.cities.add(city);
    resultContainer.districts.addAll(context.districts);
    resultContainer.buildings.addAll(context.buildings);
  }

  private static void flattenDistrict(
      final DistrictConvertible districtConvertible, final Context context) {
    final Collection<? extends DistrictConvertible> subdistricts =
        districtConvertible.getDistricts();
    final Collection<? extends BuildingConvertible> buildings = districtConvertible.getBuildings();

    final DistrictDto district =
        new DistrictDto(
            new FlatBaseModel(
                districtConvertible.getId(),
                districtConvertible.getName(),
                appendToFqn(context.parentFqn, districtConvertible.getName()),
                districtConvertible.getOriginOfData(),
                districtConvertible.getCommitComparison()),
            context.parentCityId,
            context.parentDistrictId,
            subdistricts.stream().map(DistrictConvertible::getId).toList(),
            buildings.stream().map(BuildingConvertible::getId).toList());

    final Context childrenContext = context.withParent(district);

    subdistricts.forEach(d -> flattenDistrict(d, childrenContext));
    buildings.forEach(b -> flattenBuilding(b, childrenContext));
  }

  private static void flattenBuilding(
      final BuildingConvertible buildingConvertible, final Context context) {
    final BuildingDto building =
        new BuildingDto(
            new FlatBaseModel(
                buildingConvertible.getId(),
                buildingConvertible.getName(),
                appendToFqn(context.parentFqn(), buildingConvertible.getName()),
                buildingConvertible.getOriginOfData(),
                buildingConvertible.getCommitComparison()),
            context.parentCityId,
            context.parentDistrictId,
            buildingConvertible.getLanguage(),
            buildingConvertible.getMetrics());

    context.withParent(building);
  }

  private static String appendToFqn(final String parentFqn, final String nameToAppend) {
    return parentFqn.isEmpty() ? nameToAppend : parentFqn + "/" + nameToAppend;
  }
}
