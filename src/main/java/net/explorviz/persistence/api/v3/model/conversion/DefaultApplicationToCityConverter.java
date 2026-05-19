package net.explorviz.persistence.api.v3.model.conversion;

import java.util.Collection;
import java.util.Map;
import net.explorviz.persistence.api.v3.model.CommitComparison;
import net.explorviz.persistence.api.v3.model.MetricValue;
import net.explorviz.persistence.api.v3.model.TypeOfAnalysis;
import net.explorviz.persistence.api.v3.model.landscape.BuildingDto.BuildingConvertible;
import net.explorviz.persistence.api.v3.model.landscape.CityDto.CityConvertible;
import net.explorviz.persistence.api.v3.model.landscape.DistrictDto.DistrictConvertible;
import net.explorviz.persistence.ogm.Application;
import net.explorviz.persistence.ogm.Directory;
import net.explorviz.persistence.ogm.FileRevision;

/**
 * Provides wrapper classes for turning single OGM Application objects into FlatLandscape city
 * models. Optionally allows specifying a fixed origin of data and commit comparison value for the
 * whole city.
 */
public final class DefaultApplicationToCityConverter {

  private DefaultApplicationToCityConverter() {}

  /**
   * Creates a {@link CityConvertible} out of the provided application. Uses the following mapping:
   *
   * <ul>
   *   <li>OGM Application -> City
   *   <li>OGM Directory -> District
   *   <li>OGM FileRevision -> Building
   * </ul>
   *
   * <p>The supplied originOfData is set for each converted model object.
   *
   * @param ogmApp OGM Application object. Should be hydrated to the desired depth.
   * @param originOfData The origin of data to set for each created model object.
   * @return A {@link CityConvertible} according to the above mapping.
   */
  public static CityConvertible convert(
      final Application ogmApp, final TypeOfAnalysis originOfData) {
    return new ApplicationWrapper(ogmApp, originOfData, null);
  }

  /**
   * Creates a {@link CityConvertible} out of the provided application. Uses the following mapping:
   *
   * <ul>
   *   <li>OGM Application -> City
   *   <li>OGM Directory -> District
   *   <li>OGM FileRevision -> Building
   * </ul>
   *
   * @param ogmApp OGM Application object. Should be hydrated to the desired depth.
   * @return A {@link CityConvertible} according to the above mapping.
   */
  public static CityConvertible convert(final Application ogmApp) {
    return convert(ogmApp, null);
  }

  record ApplicationWrapper(
      Application ogmApp, TypeOfAnalysis originOfData, CommitComparison commitComparison)
      implements CityConvertible {

    @Override
    public String getId() {
      return ogmApp.getId().toString();
    }

    @Override
    public String getName() {
      return ogmApp.getName();
    }

    @Override
    public TypeOfAnalysis getOriginOfData() {
      return originOfData;
    }

    @Override
    public CommitComparison getCommitComparison() {
      return commitComparison;
    }

    @Override
    public Collection<? extends DistrictConvertible> getDistricts() {
      return ogmApp.getRootDirectory().getSubdirectories().stream()
          .map(d -> new DirectoryWrapper(d, originOfData, commitComparison))
          .toList();
    }

    @Override
    public Collection<? extends BuildingConvertible> getBuildings() {
      return ogmApp.getRootDirectory().getFileRevisions().stream()
          .map(f -> new FileRevisionWrapper(f, originOfData, commitComparison))
          .toList();
    }
  }

  record DirectoryWrapper(
      Directory ogmDir, TypeOfAnalysis originOfData, CommitComparison commitComparison)
      implements DistrictConvertible {

    @Override
    public String getId() {
      return ogmDir.getId().toString();
    }

    @Override
    public String getName() {
      return ogmDir.getName();
    }

    @Override
    public TypeOfAnalysis getOriginOfData() {
      return originOfData;
    }

    @Override
    public CommitComparison getCommitComparison() {
      return commitComparison;
    }

    @Override
    public Collection<? extends DistrictConvertible> getDistricts() {
      return ogmDir.getSubdirectories().stream()
          .map(d -> new DirectoryWrapper(d, originOfData, commitComparison))
          .toList();
    }

    @Override
    public Collection<? extends BuildingConvertible> getBuildings() {
      return ogmDir.getFileRevisions().stream()
          .map(f -> new FileRevisionWrapper(f, originOfData, commitComparison))
          .toList();
    }
  }

  record FileRevisionWrapper(
      FileRevision ogmFile, TypeOfAnalysis originOfData, CommitComparison commitComparison)
      implements BuildingConvertible {

    @Override
    public String getId() {
      return ogmFile.getId().toString();
    }

    @Override
    public String getName() {
      return ogmFile.getName();
    }

    @Override
    public TypeOfAnalysis getOriginOfData() {
      return originOfData;
    }

    @Override
    public CommitComparison getCommitComparison() {
      return commitComparison;
    }

    @Override
    public String getLanguage() {
      return ogmFile.getLanguage();
    }

    @Override
    public Map<String, MetricValue> getMetrics() {
      return MetricValue.fromMap(ogmFile.getMetrics());
    }
  }
}
