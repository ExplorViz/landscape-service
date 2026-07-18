package net.explorviz.landscape.repository.metrics;

import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;

public class CoreContributorActivity extends SocialMetric {

  private final CommitActivity commitActivity = new CommitActivity();

  @Override
  public String getId() {
    return "coreContributorActivity";
  }

  // will probably be replaced by contributor subset selection eventually
  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {
    return commitActivity.computeMetric(
        new MetricInput(
            input.base(),
            input.snapshot(),
            input.coreContributorIds(),
            input.coreContributorIds()));
  }
}
