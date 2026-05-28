package net.explorviz.landscape.api.v2.model.landscape;

import java.util.List;

/**
 * In the v2-API, the Application is the root parent container for all cities in the visualization
 * landscape.
 *
 * @param name Name of the application, e.g. "petclinic-demo"
 * @param language String representing the language of the application, e.g. "JAVA"
 * @param instanceId String identifier of the particular instance of this application in case
 *     multiple instances of the application are present
 * @param packages The packages that this application comprises
 */
public record ApplicationDto(
    String name, String language, String instanceId, List<PackageDto> packages) {
  public ApplicationDto(final net.explorviz.landscape.ogm.Application ogmApp) {
    this(ogmApp.getName(), "", "", List.of(new PackageDto(ogmApp.getRootDirectory(), 0)));
  }
}
