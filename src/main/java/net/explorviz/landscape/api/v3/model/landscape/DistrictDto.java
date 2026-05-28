package net.explorviz.landscape.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto.BuildingConvertible;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel.FlatConvertible;

/**
 * Inner grouping container / divider which groups buildings and subdistricts within a city to
 * indicate organizational hierarchies. Districts represent groupings of visualization objects, such
 * as folders or packages.
 *
 * @param flatBaseModel Container for attributes shared by all flat data objects
 * @param parentCityId The ID of the city in which this building resides. Districts must always have
 *     a parent city, although it may be transitively via parent districts
 * @param parentDistrictId The ID of the district of which this district is a direct subdistrict.
 *     Districts that appear directly on a city do not have a parent district
 * @param districtIds ID values of all districts which are <strong>direct descendants</strong> of
 *     this district, i.e. with no subdistricts in between
 * @param buildingIds ID values of all buildings which are <strong>direct descendants</strong> of
 *     this district, i.e. with no subdistricts in between.
 */
@RegisterForReflection
public record DistrictDto(
    @JsonUnwrapped FlatBaseModel flatBaseModel,
    String parentCityId,
    @JsonInclude(Include.NON_NULL) String parentDistrictId,
    List<String> districtIds,
    List<String> buildingIds) {

  public DistrictDto {
    Objects.requireNonNull(flatBaseModel);
    Objects.requireNonNull(parentCityId);
    Objects.requireNonNull(districtIds);
    Objects.requireNonNull(buildingIds);
  }

  /** Must be implemented by any object which can be represented as a district during flattening. */
  public interface DistrictConvertible extends FlatConvertible {
    Collection<? extends DistrictConvertible> getDistricts();

    Collection<? extends BuildingConvertible> getBuildings();
  }
}
