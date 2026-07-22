package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.repository.SocialMetricsRepository.MergedPrStats;
import net.explorviz.landscape.util.MetricNormalizer;

public class ReviewFriction extends SocialMetric {

  private static final double LIFETIME_WEIGHT = 0.5d;

  @Override
  public String getId() {
    return "reviewFriction";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {

    final List<MergedPrStats> prs = input.mergedPrStats();

    final double[] lifetimes = new double[prs.size()];
    final double[] comments = new double[prs.size()];
    for (int i = 0; i < prs.size(); i++) {
      lifetimes[i] = prs.get(i).lifetime();
      comments[i] = prs.get(i).commentCount();
    }

    final MetricNormalizer lifeNormalizer =
        new MetricNormalizer(lifetimes, input.normalizationOpts());
    final MetricNormalizer commentNormalizer =
        new MetricNormalizer(comments, input.normalizationOpts());

    final Map<String, Integer> countByPath = new LinkedHashMap<>();
    final Map<String, Double> sumByPath = new LinkedHashMap<>();

    for (final MergedPrStats pr : input.mergedPrStats()) {
      final double score =
          LIFETIME_WEIGHT * lifeNormalizer.normalize(pr.lifetime())
              + (1 - LIFETIME_WEIGHT) * commentNormalizer.normalize(pr.commentCount());
      for (final String path : pr.paths()) {
        countByPath.merge(path, 1, Integer::sum);
        sumByPath.merge(path, score, Double::sum);
      }
    }

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (final FileSnapshot file : input.snapshot()) {
      final double score =
          countByPath.getOrDefault(file.path(), 0) > 0
              ? sumByPath.get(file.path()) / countByPath.get(file.path())
              : 0;
      scoreByFileRevisionId.put(file.fileRevisionId(), new MetricScore(score, score));
    }

    return scoreByFileRevisionId;
  }
}
