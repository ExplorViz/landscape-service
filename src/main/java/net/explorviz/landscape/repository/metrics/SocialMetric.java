package net.explorviz.landscape.repository.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.repository.SocialMetricsRepository.RepoTimeBounds;

public abstract class SocialMetric {

  public record MetricInput(
      List<ContributorFileActivity> base,
      List<FileSnapshot> snapshot,
      Set<Long> contributorIds,
      Set<Long> coreContributorIds,
      RepoTimeBounds repoTimeBounds) {}

  public abstract String getId();

  public abstract Map<Long, MetricScore> computeMetric(MetricInput input);
}
