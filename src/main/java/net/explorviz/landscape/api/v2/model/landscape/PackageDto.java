package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;
import net.explorviz.landscape.ogm.Directory;

/**
 * In the v2-API, the package is the default grouping container / divider which groups classes and
 * subpackages within an application to indicate organizational hierarchies.
 *
 * @param name Unqualified name of the package, e.g. "org"
 * @param level Number indicating the depth of the package within the application hierarchy.
 *     Increases by 1 with every layer
 * @param subPackages Child packages contained directly within this package
 * @param classes Classes contained directly within this package
 */
public record PackageDto(
    String name, int level, List<PackageDto> subPackages, List<ClazzDto> classes) {

  /**
   * Creates a new package from the given OGM directory. Note that since the v2-API assumes classes
   * as the default unit of analysis, we don't consider any top-level functions of the file in the
   * conversion.
   */
  public PackageDto(final Directory ogmDir, final int level) {
    this(
        ogmDir.getName(),
        level,
        ogmDir.getSubdirectories().stream().map(d -> new PackageDto(d, level + 1)).toList(),
        ogmDir.getFileRevisions().stream()
            .flatMap(f -> f.getClasses().stream().map(c -> new ClazzDto(c, level + 1)))
            .toList());
  }
}
