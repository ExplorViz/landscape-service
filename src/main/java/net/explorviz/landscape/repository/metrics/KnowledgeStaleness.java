package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.util.SocialMetricsHelper;

// inverse could also be interesting
public class KnowledgeStaleness extends SocialMetric {

  @Override
  public String getId() {
    return "knowledgeStaleness";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {
    final Map<String, Long> lastCommitByPath = new LinkedHashMap<>();
    for (final ContributorFileActivity fileActivity : input.base()) {
      if (SocialMetricsHelper.includes(input.contributorIds(), fileActivity.contributorId())) {
        lastCommitByPath.merge(fileActivity.path(), fileActivity.lastDate(), Long::max);
      }
    }

    final long tEnd = input.repoTimeBounds().lastDate(); // differs from thesis definition!!!
    final long tInit = input.repoTimeBounds().initDate();
    final long timespan = tEnd - tInit;

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (final FileSnapshot file : input.snapshot()) {
      final Long tLast = lastCommitByPath.get(file.path());
      final double score;
      if (tLast == null) {
        score = 1d;
      } else if (timespan > 0) {
        score = Math.max(0d, Math.min(1d, (double) (tEnd - tLast) / timespan));
      } else {
        score = 0d;
      }
      scoreByFileRevisionId.put(file.fileRevisionId(), new MetricScore(score, score));
    }
    return scoreByFileRevisionId;
  }
}
