package net.explorviz.persistence.repository;

import net.explorviz.persistence.api.v3.model.CommitComparison;
import net.explorviz.persistence.api.v3.model.landscape.FlatBaseModel;

final class LandscapeBaseModelDiffApplier {

  private LandscapeBaseModelDiffApplier() {}

  static FlatBaseModel apply(
      final FlatBaseModel base, final String newId, final CommitComparison comp) {
    return new FlatBaseModel(newId, base.name(), base.fqn(), base.originOfData(), comp);
  }
}
