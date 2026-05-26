package net.explorviz.persistence.api.v3.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TypeOfAnalysis {
  RUNTIME("runtime"),
  STATIC("static"),
  DEBUG_STATIC("debug_static"),
  DEBUG_RUNTIME("debug_runtime");

  private final String name;

  TypeOfAnalysis(final String name) {
    this.name = name;
  }

  @JsonValue
  @Override
  public String toString() {
    return name;
  }
}
