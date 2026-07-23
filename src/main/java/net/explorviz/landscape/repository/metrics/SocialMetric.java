package net.explorviz.landscape.repository.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.repository.SocialMetricsRepository.MergedPrStats;
import net.explorviz.landscape.repository.SocialMetricsRepository.RepoTimeBounds;
import net.explorviz.landscape.util.MetricNormalizer.NormalizationOptions;

public abstract class SocialMetric {

  public record MetricInput(
      List<ContributorFileActivity> base,
      List<FileSnapshot> snapshot,
      Set<Long> contributorIds,
      Set<Long> coreContributorIds,
      RepoTimeBounds repoTimeBounds,
      Map<String, Long> issueCountByPath,
      List<MergedPrStats> mergedPrStats,
      NormalizationOptions normalizationOpts) {}

  public abstract String getId();

  public abstract Map<Long, MetricScore> computeMetric(MetricInput input);
}
