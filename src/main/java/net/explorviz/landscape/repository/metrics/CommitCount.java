package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.FileSnapshot;

public class CommitCount extends SocialMetric {

  @Override
  public String getId() {
    return "commitCount";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {
    final Map<String, Long> countByPath =
        CommitActivity.getCommitCountByPath(input.base(), input.contributorIds());

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (final FileSnapshot file : input.snapshot()) {
      final long score = countByPath.getOrDefault(file.path(), 0L);
      scoreByFileRevisionId.put(file.fileRevisionId(), new MetricScore(score, score));
    }
    return scoreByFileRevisionId;
  }
}
