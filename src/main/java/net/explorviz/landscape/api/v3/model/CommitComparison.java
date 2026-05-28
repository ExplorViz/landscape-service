package net.explorviz.landscape.api.v3.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CommitComparison {
  ADDED("ADDED"),
  MODIFIED("MODIFIED"),
  REMOVED("REMOVED"),
  UNCHANGED("UNCHANGED");

  private final String name;

  CommitComparison(final String name) {
    this.name = name;
  }

  @JsonValue
  @Override
  public String toString() {
    return name;
  }
}
