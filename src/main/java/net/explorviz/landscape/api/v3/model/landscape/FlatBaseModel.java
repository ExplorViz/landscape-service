package net.explorviz.landscape.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Objects;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.TypeOfAnalysis;

/**
 * Contains base attributes shared by all visualization objects in the flat landscape model.
 *
 * @param id Identifier that is unique across the entire landscape
 * @param name Name to display for this visualization object. Need not be unique
 * @param fqn The fully-qualified name for this visualization object, if applicable. This allows
 *     faster lookup than having to construct it from the visualization hierarchy
 * @param originOfData Analysis method through which this object was discovered
 * @param commitComparison Only applicable to git analysis: Indicates this object's relationship
 *     regarding two selected commits
 */
@RegisterForReflection
public record FlatBaseModel(
    String id,
    String name,
    @JsonInclude(Include.NON_NULL) String fqn,
    @JsonInclude(Include.NON_NULL) TypeOfAnalysis originOfData,
    @JsonInclude(Include.NON_NULL) CommitComparison commitComparison) {

  public FlatBaseModel {
    Objects.requireNonNull(id);
    Objects.requireNonNull(name);
  }

  /** Must be implemented by any object which can be represented as a flat model. */
  public interface FlatConvertible {
    String getId();

    String getName();

    default TypeOfAnalysis getOriginOfData() {
      return null;
    }

    default CommitComparison getCommitComparison() {
      return null;
    }
  }
}
