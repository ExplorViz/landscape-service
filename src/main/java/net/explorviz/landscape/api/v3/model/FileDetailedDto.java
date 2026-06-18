package net.explorviz.landscape.api.v3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public record FileDetailedDto(
    String name,
    String language,
    String packageName,
    List<String> importNames,
    int addedLines,
    int modifiedLines,
    int deletedLines,
    Map<String, Double> metrics,
    List<ClazzDto> classes,
    List<FunctionDto> functions,
    String fileUrl) {
  @RegisterForReflection
  public record ClazzDto(
      String name,
      String type,
      Map<String, Double> metrics,
      List<FunctionDto> functions,
      List<FieldDto> fields,
      List<ClazzDto> innerClasses,
      List<String> superclassFqns) {}

  @RegisterForReflection
  public record FunctionDto(String name, String returnType, Map<String, Double> metrics) {}

  @RegisterForReflection
  public record FieldDto(String name, String type) {}
}
