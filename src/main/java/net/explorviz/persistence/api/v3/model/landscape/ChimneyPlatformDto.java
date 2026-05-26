package net.explorviz.persistence.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Objects;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel.FlatConvertible;

/**
 * The smallest unit of visualization in the city metaphor. Chimneys represent variables captured in
 * snapshots taken during a debugging session pause
 *
 * @param flatBaseModel Container for attributes shared by all flat data objects
 * @param parentCityId The ID of the city in which this chimney platform resides. Chimneys platforms
 *     must always have a parent city, although it may be transitively via some buildings and
 *     districts
 * @param parentBuildingId The ID of the building of which this chimney platform is a direct child.
 */
@RegisterForReflection
public record ChimneyPlatformDto(
    @JsonUnwrapped FlatBaseModel flatBaseModel,
    String parentCityId,
    @JsonInclude(Include.NON_NULL) String instanceId,
    @JsonInclude(Include.NON_NULL) String parentBuildingId,
    List<String> chimneyIds) {

  public ChimneyPlatformDto {
    Objects.requireNonNull(flatBaseModel);
    Objects.requireNonNull(parentCityId);
  }

  /**
   * Must be implemented by any object which can be represented as a chimney platform during
   * flattening.
   */
  public interface ChimneyPlatformConvertible extends FlatConvertible {}
}
