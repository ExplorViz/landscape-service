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

/** Abstract class for a social metric to be registered in SocialMetricsRepository. */
public abstract class SocialMetric {

  /**
   * A record containing all information needed to calculate every social metric.
   *
   * @param base the base aggregation query results
   * @param snapshot the file snapshot query results
   * @param contributorIds the set of contributorIds selected
   * @param coreContributorIds the set of core contributors
   * @param repoTimeBounds the time range of first to last commit in the analysis
   * @param issueCountByPath the issue count query results
   * @param mergedPrStats the merged pr stats query results
   * @param normalizationOpts the normalization options record
   */
  public record MetricInput(
      List<ContributorFileActivity> base,
      List<FileSnapshot> snapshot,
      Set<Long> contributorIds,
      Set<Long> coreContributorIds,
      RepoTimeBounds repoTimeBounds,
      Map<String, Long> issueCountByPath,
      List<MergedPrStats> mergedPrStats,
      NormalizationOptions normalizationOpts) {}

  /**
   * The static metric identifier.
   *
   * @return String identifier
   */
  public abstract String getId();

  /**
   * The main metric calculation.
   *
   * @param input the MetricInput record used for calculation
   * @return Map of fileRevisionId to MetricScore records
   */
  public abstract Map<Long, MetricScore> computeMetric(MetricInput input);
}
