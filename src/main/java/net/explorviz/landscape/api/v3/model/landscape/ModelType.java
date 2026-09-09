package net.explorviz.landscape.api.v3.model.landscape;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Classification of flat landscape models giving more detail on their role / origin. This can be
 * used to filter or visually distinguish models that come from different analysis sources. Any set
 * of models which the user might want to distinguish from the rest of the visualization should
 * become its own type.
 */
public enum ModelType {
  /** Models of unknown type. To be used as a fallback only. */
  UNKNOWN("unknown"),

  /** Models representing applications or services. */
  SERVICE("service"),

  /** Models representing OpenTelemetry instrumentation scopes. */
  INSTRUMENTATION_SCOPE("instrumentation_scope"),

  /** Models originating from source code analysis. */
  CODE("code"),

  /** Models originating from remote procedure call analysis. */
  RPC("rpc"),

  /** Models originating from HTTP API analysis. */
  HTTP("http");

  private final String name;

  ModelType(final String name) {
    this.name = name;
  }

  @JsonValue
  @Override
  public String toString() {
    return name;
  }
}
