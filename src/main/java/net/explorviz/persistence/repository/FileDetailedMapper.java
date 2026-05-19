package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.stream.Collectors;
import net.explorviz.persistence.api.v3.model.FileDetailedDto;
import net.explorviz.persistence.api.v3.model.FileDetailedDto.ClazzDto;
import net.explorviz.persistence.api.v3.model.FileDetailedDto.FieldDto;
import net.explorviz.persistence.api.v3.model.FileDetailedDto.FunctionDto;
import net.explorviz.persistence.ogm.Clazz;
import net.explorviz.persistence.ogm.Field;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.ogm.Function;

@ApplicationScoped
public class FileDetailedMapper {

  public FileDetailedDto map(final FileRevision fileRevision) {
    return new FileDetailedDto(
        fileRevision.getName(),
        fileRevision.getLanguage(),
        fileRevision.getPackageName(),
        fileRevision.getAddedLines(),
        fileRevision.getModifiedLines(),
        fileRevision.getDeletedLines(),
        fileRevision.getMetrics(),
        fileRevision.getClasses().stream().map(this::mapClazz).collect(Collectors.toList()),
        fileRevision.getFunctions().stream().map(this::mapFunction).collect(Collectors.toList()));
  }

  private ClazzDto mapClazz(final Clazz clazz) {
    return new ClazzDto(
        clazz.getName(),
        clazz.getType() != null ? clazz.getType().name() : null,
        clazz.getMetrics(),
        clazz.getFunctions().stream().map(this::mapFunction).collect(Collectors.toList()),
        clazz.getFields().stream().map(this::mapField).collect(Collectors.toList()),
        clazz.getInnerClasses().stream().map(this::mapClazz).collect(Collectors.toList()));
  }

  private FunctionDto mapFunction(final Function function) {
    return new FunctionDto(function.getName(), function.getReturnType(), function.getMetrics());
  }

  private FieldDto mapField(final Field field) {
    return new FieldDto(field.getName(), field.getType());
  }
}
