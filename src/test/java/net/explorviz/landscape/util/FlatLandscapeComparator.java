package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;

/** Testing utility that can assert equality for the structure of landscape objects. */
public class FlatLandscapeComparator {

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
