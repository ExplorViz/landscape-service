package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.FileRevision;

/**
 * The class (clazz) is the unit of visualization in the v2-API, which are represented by buildings
 * in the software landscape.
 *
 * @param name Name of the class / building
 * @param level Number indicating the depth of the package within the application hierarchy. *
 *     Increases by 1 with every layer
 * @param methods The functions that are contained in this class. Note the naming is due to
 *     compatibility with the v2-API
 */
public record ClazzDto(String name, int level, List<FunctionDto> methods) {

  public ClazzDto(final Clazz ogmClass, final int level) {
    this(
        ogmClass.getName(), level, ogmClass.getFunctions().stream().map(FunctionDto::new).toList());
  }

  public ClazzDto(final FileRevision ogmFile, final int level) {
    this(ogmFile.getName(), level, ogmFile.getFunctions().stream().map(FunctionDto::new).toList());
  }
}
