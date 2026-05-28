package net.explorviz.landscape.api.v3.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TypeOfAnalysis {
  RUNTIME("runtime"),
  STATIC("static");

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
