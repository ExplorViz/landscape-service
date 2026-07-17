package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.proto.CodeDescriptor;

public class FlatLandscapeComparator {

  /**
   * Takes in a list of {@link CodeDescriptor}s and directly constructs a {@link FlatLandscapeDto}
   * from it to be used for tests, where the cities correspond to the applications, directories are
   * represented by districts, and the files within them by buildings. The {@link TypeOfAnalysis} is
   * set as runtime and the telemetry key is set according to the IDs in the descriptor.
   */
  public static FlatLandscapeDto landscapeFromCodeEntities(
      final String landscapeTokenId, Collection<CodeDescriptor> descriptors) {

    FlatLandscapeDto landscape =
        new FlatLandscapeDto(landscapeTokenId, new HashMap<>(), new HashMap<>(), new HashMap<>());

    for (CodeDescriptor descriptor : descriptors) {
      String[] filePath = descriptor.getFilePath().split("/");

      CityDto city =
          landscape.cities().values().stream()
              .filter(c -> c.flatBaseModel().name().equals(descriptor.getApplicationName()))
              .findAny()
              .orElse(
                  new CityDto(
                      new FlatBaseModel(
                          UUID.randomUUID().toString(),
                          descriptor.getApplicationName(),
                          null,
                          null,
                          TypeOfAnalysis.RUNTIME,
                          null),
                      new ArrayList<>(),
                      new ArrayList<>(),
                      new ArrayList<>(),
                      new ArrayList<>()));

      landscape.cities().putIfAbsent(city.flatBaseModel().id(), city);

      StringBuilder fqn = new StringBuilder();
      DistrictDto parentDistrict = null;

      for (int i = 0; i < filePath.length; i++) {
        String pathName = filePath[i];

        if (!fqn.isEmpty()) {
          fqn.append("/");
        }
        fqn.append(pathName);

        final String parentDistrictId =
            parentDistrict != null ? parentDistrict.flatBaseModel().id() : null;

        if (i == filePath.length - 1) {
          List<String> containerIdList =
              parentDistrict != null ? parentDistrict.buildingIds() : city.buildingIds();

          BuildingDto building =
              containerIdList.stream()
                  .map(id -> landscape.buildings().get(id))
                  .filter(b -> b.flatBaseModel().name().equals(pathName))
                  .findAny()
                  .orElse(
                      new BuildingDto(
                          new FlatBaseModel(
                              UUID.randomUUID().toString(),
                              pathName,
                              fqn.toString(),
                              descriptor.getFileId(),
                              TypeOfAnalysis.RUNTIME,
                              null),
                          city.flatBaseModel().id(),
                          parentDistrictId,
                          descriptor.getLanguage(),
                          Map.of()));

          landscape.buildings().putIfAbsent(building.flatBaseModel().id(), building);
          if (!city.allContainedBuildingIds().contains(building.flatBaseModel().id())) {
            city.allContainedBuildingIds().add(building.flatBaseModel().id());
          }

          if (!containerIdList.contains(building.flatBaseModel().id())) {
            containerIdList.add(building.flatBaseModel().id());
          }

          continue;
        }

        List<String> containerIdList =
            parentDistrict != null ? parentDistrict.districtIds() : city.districtIds();

        DistrictDto district =
            containerIdList.stream()
                .map(id -> landscape.districts().get(id))
                .filter(d -> d.flatBaseModel().name().equals(pathName))
                .findAny()
                .orElse(
                    new DistrictDto(
                        new FlatBaseModel(
                            UUID.randomUUID().toString(),
                            pathName,
                            fqn.toString(),
                            null,
                            TypeOfAnalysis.RUNTIME,
                            null),
                        city.flatBaseModel().id(),
                        parentDistrictId,
                        new ArrayList<>(),
                        new ArrayList<>()));

        landscape.districts().putIfAbsent(district.flatBaseModel().id(), district);
        if (!city.allContainedDistrictIds().contains(district.flatBaseModel().id())) {
          city.allContainedDistrictIds().add(district.flatBaseModel().id());
        }

        if (!containerIdList.contains(district.flatBaseModel().id())) {
          containerIdList.add(district.flatBaseModel().id());
        }

        parentDistrict = district;
      }
    }

    return landscape;
  }

  /**
   * Assert whether the IDs used within the flat landscape refer to valid landscape objects and
   * whether all parent IDs correctly reference the ID of the object the child is contained in.
   */
  public static void assertLandscapeIdsValid(FlatLandscapeDto landscape) {
    for (Map.Entry<String, CityDto> entry : landscape.cities().entrySet()) {
      final CityDto city = entry.getValue();
      assertEquals(entry.getKey(), city.flatBaseModel().id());

      for (String districtId : city.districtIds()) {
        assertLandscapeIdsValid(landscape, landscape.districts().get(districtId), city, null);
      }

      for (String buildingId : city.buildingIds()) {
        assertLandscapeIdsValid(landscape.buildings().get(buildingId), city, null);
      }
    }
  }

  private static void assertLandscapeIdsValid(
      FlatLandscapeDto landscape,
      DistrictDto district,
      CityDto parentCity,
      String parentDistrictId) {

    assertEquals(parentCity.flatBaseModel().id(), district.parentCityId());
    assertEquals(parentDistrictId, district.parentDistrictId());
    assertTrue(parentCity.allContainedDistrictIds().contains(district.flatBaseModel().id()));

    for (String districtId : district.districtIds()) {
      assertLandscapeIdsValid(
          landscape,
          landscape.districts().get(districtId),
          parentCity,
          district.flatBaseModel().id());
    }

    for (String buildingId : district.buildingIds()) {
      assertLandscapeIdsValid(
          landscape.buildings().get(buildingId), parentCity, district.flatBaseModel().id());
    }
  }

  private static void assertLandscapeIdsValid(
      BuildingDto building, CityDto parentCity, String parentDistrictId) {

    assertEquals(parentCity.flatBaseModel().id(), building.parentCityId());
    assertEquals(parentDistrictId, building.parentDistrictId());
    assertTrue(parentCity.allContainedBuildingIds().contains(building.flatBaseModel().id()));
  }

  /**
   * Assert that the structure of landscape {@code expected} equals that of {@code actual}.
   * Concretely, it is verified that the names of cities, districts and buildings are equal and that
   * the hierarchy of landscape objects is the same. If the {@code actual} landscape has additional
   * objects that are not present in {@code expected}, the assertion fails. Next to the name, the
   * fqn values for districts and buildings and the landscape token of the landscapes are also
   * verified to match. For buildings, the telemetry key is also verified.
   */
  public static void assertLandscapeStructureMatching(
      FlatLandscapeDto expected, FlatLandscapeDto actual) {

    assertEquals(expected.landscapeToken(), actual.landscapeToken());
    assertEquals(expected.cities().size(), actual.cities().size());
    assertEquals(expected.districts().size(), actual.districts().size());
    assertEquals(expected.buildings().size(), actual.buildings().size());

    for (Map.Entry<String, CityDto> entry : expected.cities().entrySet()) {
      CityDto expectedCity = entry.getValue();
      CityDto actualCity = null;
      for (CityDto city : actual.cities().values()) {
        if (city.flatBaseModel().name().equals(expectedCity.flatBaseModel().name())) {
          actualCity = city;
          break;
        }
      }
      assertNotNull(
          actualCity,
          "Could not find city with matching name " + expectedCity.flatBaseModel().name());

      assertCityStructureMatching(expected, actual, expectedCity, actualCity);
    }
  }

  private static void assertCityStructureMatching(
      FlatLandscapeDto expectedLandscape,
      FlatLandscapeDto actualLandscape,
      CityDto expectedCity,
      CityDto actualCity) {

    assertEquals(expectedCity.flatBaseModel().name(), actualCity.flatBaseModel().name());
    assertEquals(
        expectedCity.allContainedBuildingIds().size(), actualCity.allContainedBuildingIds().size());
    assertEquals(
        expectedCity.allContainedDistrictIds().size(), actualCity.allContainedDistrictIds().size());
    assertEquals(expectedCity.buildingIds().size(), actualCity.buildingIds().size());
    assertEquals(expectedCity.districtIds().size(), actualCity.districtIds().size());

    for (String districtId : expectedCity.districtIds()) {
      DistrictDto expectedDistrict = expectedLandscape.districts().get(districtId);
      DistrictDto actualDistrict = null;
      for (String id : actualCity.districtIds()) {
        DistrictDto foundDistrict = actualLandscape.districts().get(id);
        assertNotNull(
            foundDistrict,
            "District with ID %s referenced in city %s does not exist in landscape %s"
                .formatted(
                    id, actualCity.flatBaseModel().name(), actualLandscape.landscapeToken()));
        if (foundDistrict.flatBaseModel().name().equals(expectedDistrict.flatBaseModel().name())) {
          actualDistrict = foundDistrict;
          break;
        }
      }
      assertNotNull(
          actualDistrict,
          "Could not find child district with name " + expectedDistrict.flatBaseModel().name());

      assertDistrictStructureMatching(
          expectedLandscape, actualLandscape, expectedDistrict, actualDistrict);
    }

    for (String buildingId : expectedCity.buildingIds()) {
      BuildingDto expectedBuilding = expectedLandscape.buildings().get(buildingId);
      BuildingDto actualBuilding = null;
      for (String id : actualCity.buildingIds()) {
        BuildingDto foundBuilding = actualLandscape.buildings().get(id);
        assertNotNull(
            foundBuilding,
            "Building with ID %s referenced in city %s does not exist in landscape %s"
                .formatted(
                    id, actualCity.flatBaseModel().name(), actualLandscape.landscapeToken()));
        if (foundBuilding.flatBaseModel().name().equals(expectedBuilding.flatBaseModel().name())) {
          actualBuilding = foundBuilding;
          break;
        }
      }
      assertNotNull(
          actualBuilding,
          "Could not find child building with name " + expectedBuilding.flatBaseModel().name());

      assertBuildingStructureMatching(expectedBuilding, actualBuilding);
    }
  }

  private static void assertDistrictStructureMatching(
      FlatLandscapeDto expectedLandscape,
      FlatLandscapeDto actualLandscape,
      DistrictDto expectedDistrict,
      DistrictDto actualDistrict) {

    assertEquals(expectedDistrict.flatBaseModel().name(), actualDistrict.flatBaseModel().name());
    assertEquals(expectedDistrict.flatBaseModel().fqn(), actualDistrict.flatBaseModel().fqn());
    assertEquals(expectedDistrict.buildingIds().size(), actualDistrict.buildingIds().size());
    assertEquals(expectedDistrict.districtIds().size(), actualDistrict.districtIds().size());

    for (String districtId : expectedDistrict.districtIds()) {
      DistrictDto expectedChildDistrict = expectedLandscape.districts().get(districtId);
      DistrictDto actualChildDistrict = null;
      for (String id : actualDistrict.districtIds()) {
        DistrictDto foundDistrict = actualLandscape.districts().get(id);
        assertNotNull(
            foundDistrict,
            "District with ID %s referenced in district %s does not exist in landscape %s"
                .formatted(
                    id, actualDistrict.flatBaseModel().fqn(), actualLandscape.landscapeToken()));
        if (foundDistrict
            .flatBaseModel()
            .name()
            .equals(expectedChildDistrict.flatBaseModel().name())) {
          actualChildDistrict = foundDistrict;
          break;
        }
      }
      assertNotNull(
          actualChildDistrict,
          "Could not find child district with name "
              + expectedChildDistrict.flatBaseModel().name());

      assertDistrictStructureMatching(
          expectedLandscape, actualLandscape, expectedChildDistrict, actualChildDistrict);
    }

    for (String buildingId : expectedDistrict.buildingIds()) {
      BuildingDto expectedBuilding = expectedLandscape.buildings().get(buildingId);
      BuildingDto actualBuilding = null;
      for (String id : actualDistrict.buildingIds()) {
        BuildingDto foundBuilding = actualLandscape.buildings().get(id);
        assertNotNull(
            foundBuilding,
            "Building with ID %s referenced in district %s does not exist in landscape %s"
                .formatted(
                    id, actualDistrict.flatBaseModel().fqn(), actualLandscape.landscapeToken()));
        if (foundBuilding.flatBaseModel().name().equals(expectedBuilding.flatBaseModel().name())) {
          actualBuilding = foundBuilding;
          break;
        }
      }
      assertNotNull(
          actualBuilding,
          "Could not find child building with name " + expectedBuilding.flatBaseModel().name());

      assertBuildingStructureMatching(expectedBuilding, actualBuilding);
    }
  }

  private static void assertBuildingStructureMatching(
      BuildingDto expectedBuilding, BuildingDto actualBuilding) {

    assertEquals(expectedBuilding.flatBaseModel().name(), actualBuilding.flatBaseModel().name());
    assertEquals(expectedBuilding.flatBaseModel().fqn(), actualBuilding.flatBaseModel().fqn());
    assertEquals(
        expectedBuilding.flatBaseModel().telemetryKey(),
        actualBuilding.flatBaseModel().telemetryKey());
  }
}
