package net.explorviz.persistence.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Map;
import java.util.Objects;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel.FlatConvertible;

/**
 * The smallest unit of visualization in the city metaphor. Chimneys represent variables captured in
 * snapshots taken during a debugging session pause
 *
 * @param flatBaseModel Container for attributes shared by all flat data objects
 * @param parentCityId The ID of the city in which this chimney resides. Chimneys must always have a
 *     parent city, although it may be transitively via some buildings and districts
 * @param parentBuildingId The ID of the building of which this chimney is a direct child. Buildings
 *     that appear directly on a city do not have a parent district
 * @param value The current value of the variable represented by this chimney
 * @param metrics Metrics for this unit, i.e. numerical measurements gathered during the debugging
 *     session, such as the number of modifications since the variable started being monitored
 */
@RegisterForReflection
public record ChimneyDto(
    @JsonUnwrapped FlatBaseModel flatBaseModel,
    String parentCityId,
    @JsonInclude(Include.NON_NULL) String parentBuildingId,
    @JsonInclude(Include.NON_NULL) String value,
    @JsonInclude(Include.NON_NULL) String type,
    @JsonInclude(Include.NON_EMPTY) Map<String, MetricValue> metrics) {

  public ChimneyDto {
    Objects.requireNonNull(flatBaseModel);
    Objects.requireNonNull(parentCityId);
  }

  /** Must be implemented by any object which can be represented as a chimney during flattening. */
  public interface ChimneyConvertible extends FlatConvertible {
    default String getValue() {
      return null;
    }

    default String getType() {
      return null;
    }

    default Map<String, MetricValue> getMetrics() {
      return Map.of();
    }
  }
}
