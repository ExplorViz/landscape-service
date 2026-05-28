package net.explorviz.landscape.util;

import java.util.HashMap;
import java.util.Map;

public class ExpectedCounts {
  private final Map<String, Long> values = new HashMap<>();

  private ExpectedCounts() {}

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, Long> build() {
    return values;
  }

  public static class Builder {
    private final Map<String, Long> values = new HashMap<>();

    public Builder landscapes(long v) {
      values.put(NodeCountType.LANDSCAPE.key(), v);
      return this;
    }

    public Builder repositories(long v) {
      values.put(NodeCountType.REPOSITORY.key(), v);
      return this;
    }

    public Builder branches(long v) {
      values.put(NodeCountType.BRANCH.key(), v);
      return this;
    }

    public Builder commits(long v) {
      values.put(NodeCountType.COMMIT.key(), v);
      return this;
    }

    public Builder tags(long v) {
      values.put(NodeCountType.TAG.key(), v);
      return this;
    }

    public Builder files(long v) {
      values.put(NodeCountType.FILE.key(), v);
      return this;
    }

    public Builder applications(long v) {
      values.put(NodeCountType.APPLICATION.key(), v);
      return this;
    }

    public Builder directories(long v) {
      values.put(NodeCountType.DIRECTORY.key(), v);
      return this;
    }

    public Builder classes(long v) {
      values.put(NodeCountType.CLAZZ.key(), v);
      return this;
    }

    public Builder fields(long v) {
      values.put(NodeCountType.FIELD.key(), v);
      return this;
    }

    public Builder functions(long v) {
      values.put(NodeCountType.FUNCTION.key(), v);
      return this;
    }

    public Builder parameters(long v) {
      values.put(NodeCountType.PARAMETER.key(), v);
      return this;
    }

    public Builder spans(long v) {
      values.put(NodeCountType.SPAN.key(), v);
      return this;
    }

    public Builder traces(long v) {
      values.put(NodeCountType.TRACE.key(), v);
      return this;
    }

    public Map<String, Long> build() {
      return values;
    }
  }
}
