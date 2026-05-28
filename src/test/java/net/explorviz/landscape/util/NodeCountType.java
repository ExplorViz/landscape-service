package net.explorviz.landscape.util;

public enum NodeCountType {
  LANDSCAPE("landscapes"),
  REPOSITORY("repositories"),
  BRANCH("branches"),
  COMMIT("commits"),
  TAG("tags"),
  FILE("files"),
  APPLICATION("applications"),
  DIRECTORY("directories"),
  CLAZZ("classes"),
  FIELD("fields"),
  FUNCTION("functions"),
  PARAMETER("parameters"),
  SPAN("spans"),
  TRACE("traces");

  private final String key;

  NodeCountType(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
