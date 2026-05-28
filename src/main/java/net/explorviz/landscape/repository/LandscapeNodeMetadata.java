package net.explorviz.landscape.repository;

import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.CityDto;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto;

final class LandscapeNodeMetadata {

  private LandscapeNodeMetadata() {}

  static String getFqn(final Object dto) {
    if (dto instanceof CityDto d) {
      return d.flatBaseModel().name();
    }
    if (dto instanceof DistrictDto d) {
      return d.flatBaseModel().fqn();
    }
    if (dto instanceof BuildingDto d) {
      return d.flatBaseModel().fqn();
    }
    return "";
  }

  static String getId(final Object dto) {
    if (dto instanceof CityDto d) {
      return d.flatBaseModel().id();
    }
    if (dto instanceof DistrictDto d) {
      return d.flatBaseModel().id();
    }
    if (dto instanceof BuildingDto d) {
      return d.flatBaseModel().id();
    }
    return "";
  }
}
