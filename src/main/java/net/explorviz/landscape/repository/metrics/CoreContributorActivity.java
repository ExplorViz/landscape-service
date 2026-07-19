package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.util.SocialMetricsHelper;

// possibly add simple inverse?
public class CoreContributorActivity extends SocialMetric {

  @Override
  public String getId() {
    return "coreContributorActivity";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {

    final Map<String, Long> totalCommitsByPath = new LinkedHashMap<>();
    final Map<String, Long> coreCommitsByPath = new LinkedHashMap<>();

    for (final ContributorFileActivity fileActivity : input.base()) {
      totalCommitsByPath.merge(fileActivity.path(), fileActivity.commits(), Long::sum);
      if (SocialMetricsHelper.includes(input.coreContributorIds(), fileActivity.contributorId())) {
        coreCommitsByPath.merge(fileActivity.path(), fileActivity.commits(), Long::sum);
      }
    }

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (final FileSnapshot file : input.snapshot()) {
      double score = 0d;
      if (totalCommitsByPath.get(file.path()) != null
          && coreCommitsByPath.get(file.path()) != null) {
        score = (double) coreCommitsByPath.get(file.path()) / totalCommitsByPath.get(file.path());
      }
      scoreByFileRevisionId.put(file.fileRevisionId(), new MetricScore(score, score));
    }
    return scoreByFileRevisionId;
  }
}
