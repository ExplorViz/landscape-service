package net.explorviz.persistence.api.v3.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DebugSnapshotComparison implements Comparison {
  ADDED("ADDED"),
  CHANGED("CHANGED"),
  REMOVED("REMOVED"),
  UNCHANGED("UNCHANGED");

  private final String name;

  DebugSnapshotComparison(final String name) {
    this.name = name;
  }

  @JsonValue
  @Override
  public String toString() {
    return name;
  }
}
