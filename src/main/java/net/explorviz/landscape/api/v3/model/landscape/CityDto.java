package net.explorviz.landscape.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto.BuildingConvertible;
import net.explorviz.landscape.api.v3.model.landscape.DistrictDto.DistrictConvertible;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel.FlatConvertible;

/**
 * Outermost grouping container for all objects which are visualized inside a landscape. Cities
 * represent cohesive systems that can operate independently, such as applications, where units work
 * together for a larger purpose.
 *
 * @param flatBaseModel Container for attributes shared by all flat data objects
 * @param districtIds ID values of all districts which are <strong>direct descendants</strong> of
 *     this city, i.e. with no subdistricts in between
 * @param buildingIds ID values of all buildings which are <strong>direct descendants</strong> of
 *     this city, i.e. with no subdistricts in between. These appear directly on the City model
 * @param allContainedDistrictIds ID values for all districts which appear inside this City,
 *     <strong>even transitively</strong> (i.e. nested objects). This is required for faster lookups
 * @param allContainedBuildingIds ID values for all buildings which appear inside this City,
 *     <strong>even transitively</strong> (i.e. nested objects). This is required for faster lookups
 */
@RegisterForReflection
public record CityDto(
    @JsonUnwrapped FlatBaseModel flatBaseModel,
    List<String> districtIds,
    List<String> buildingIds,
    List<String> allContainedDistrictIds,
    List<String> allContainedBuildingIds) {

  public CityDto {
    Objects.requireNonNull(flatBaseModel);
    Objects.requireNonNull(districtIds);
    Objects.requireNonNull(buildingIds);
    Objects.requireNonNull(allContainedDistrictIds);
    Objects.requireNonNull(allContainedBuildingIds);
  }

  /** Must be implemented by any object which can be represented as a city during flattening. */
  public interface CityConvertible extends FlatConvertible {
    Collection<? extends DistrictConvertible> getDistricts();

    Collection<? extends BuildingConvertible> getBuildings();
  }
}
