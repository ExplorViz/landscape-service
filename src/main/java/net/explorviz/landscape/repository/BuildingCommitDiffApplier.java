package net.explorviz.landscape.repository;

import java.util.Map;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.MetricValue;
import net.explorviz.landscape.api.v3.model.landscape.BuildingDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatBaseModel;

final class BuildingCommitDiffApplier {

  private BuildingCommitDiffApplier() {}

  static BuildingDto apply(
      final BuildingDto current,
      final BuildingDto other,
      final CommitComparison comp,
      final Map<String, String> idMap) {
    final FlatBaseModel base = current != null ? current.flatBaseModel() : other.flatBaseModel();
    final String parentCityId = current != null ? current.parentCityId() : other.parentCityId();
    final String parentDistrictId =
        current != null ? current.parentDistrictId() : other.parentDistrictId();

    final Map<String, MetricValue> metrics =
        BuildingMetricMerger.mergeForComparison(current, other, comp);

    return new BuildingDto(
        LandscapeBaseModelDiffApplier.apply(base, idMap.get(base.id()), comp),
        idMap.get(parentCityId),
        parentDistrictId != null ? idMap.get(parentDistrictId) : null,
        current != null ? current.language() : other.language(),
        metrics,
        current != null ? current.fileHash() : other.fileHash());
  }
}
