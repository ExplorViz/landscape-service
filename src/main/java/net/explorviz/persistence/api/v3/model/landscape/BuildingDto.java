package net.explorviz.persistence.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;
import java.util.Objects;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel.FlatConvertible;
import net.explorviz.persistence.proto.Language;

/**
 * The smallest unit of visualization in the city metaphor. Buildings represent individual units of
 * analysis, such as files.
 *
 * @param flatBaseModel Container for attributes shared by all flat data objects
 * @param parentCityId The ID of the city in which this building resides. Buildings must always have
 *     a parent city, although it may be transitively via some districts
 * @param parentDistrictId The ID of the district of which this building is a direct child.
 *     Buildings that appear directly on a city do not have a parent district
 * @param language Can be used to specify a programming language if applicable, such as with files.
 *     Can be set to {@link Language#LANGUAGE_UNSPECIFIED} if the language cannot be uniquely
 *     determined
 * @param metrics Metrics for this unit, i.e. numerical measurements gathered through analysis, such
 *     as cyclomatic complexity or lines of code
 */
@RegisterForReflection
public record BuildingDto(
    @JsonUnwrapped FlatBaseModel flatBaseModel,
    String parentCityId,
    @JsonInclude(Include.NON_NULL) String parentDistrictId,
    @JsonInclude(Include.NON_NULL) String language,
    @JsonInclude(Include.NON_EMPTY) Map<String, MetricValue> metrics) {

  public BuildingDto {
    Objects.requireNonNull(flatBaseModel);
    Objects.requireNonNull(parentCityId);
  }

  /** Must be implemented by any object which can be represented as a building during flattening. */
  public interface BuildingConvertible extends FlatConvertible {
    default String getLanguage() {

      return null;
    }

    default Map<String, MetricValue> getMetrics() {
      return Map.of();
    }
  }
}
