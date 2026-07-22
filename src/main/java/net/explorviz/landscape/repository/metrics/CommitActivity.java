package net.explorviz.landscape.repository.metrics;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.util.MetricNormalizer;
import net.explorviz.landscape.util.SocialMetricsHelper;

public class CommitActivity extends SocialMetric {

  @Override
  public String getId() {
    return "commitActivity";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput metricInput) {
    // precalculate CA Map needed for normalization
    final Map<String, Long> commitCountByPath =
        getCommitCountByPath(metricInput.base(), metricInput.contributorIds());

    // filter for files actually contained in the currently viewed commit
    final double[] rawScores = new double[metricInput.snapshot().size()];
    for (int i = 0; i < metricInput.snapshot().size(); i++) {
      rawScores[i] = commitCountByPath.getOrDefault(metricInput.snapshot().get(i).path(), 0L);
    }

    final MetricNormalizer normalizer =
        new MetricNormalizer(rawScores, metricInput.normalizationOpts());

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (int i = 0; i < metricInput.snapshot().size(); i++) {
      final FileSnapshot f = metricInput.snapshot().get(i);
      scoreByFileRevisionId.put(
          f.fileRevisionId(), new MetricScore(rawScores[i], normalizer.normalize(rawScores[i])));
    }
    return scoreByFileRevisionId;
  }

  public static Map<String, Long> getCommitCountByPath(
      final List<ContributorFileActivity> base, final Set<Long> contributorIds) {
    final Map<String, Long> commitCountByPath = new HashMap<>();
    for (final ContributorFileActivity row : base) {
      if (SocialMetricsHelper.includes(contributorIds, row.contributorId())) {
        commitCountByPath.merge(row.path(), row.commits(), Long::sum);
      }
    }
    return commitCountByPath;
  }
}
