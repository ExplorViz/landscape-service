package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorFileActivity;
import net.explorviz.landscape.repository.FileSnapshot;

public class KnowledgeSilo extends SocialMetric {

  private static final int MIN_COMMITS = 5;

  @Override
  public String getId() {
    return "knowledgeSilo";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {
    final Map<String, Long> totalCommitsByPath = new LinkedHashMap<>();
    final Map<String, Long> maxContributorCommitsByPath = new LinkedHashMap<>();
    for (final ContributorFileActivity fileActivity : input.base()) {
      totalCommitsByPath.merge(fileActivity.path(), fileActivity.commits(), Long::sum);
      maxContributorCommitsByPath.merge(fileActivity.path(), fileActivity.commits(), Long::max);
    }

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (final FileSnapshot file : input.snapshot()) {
      final long total = totalCommitsByPath.getOrDefault(file.path(), 0L);
      // single commit files get default value of 1 TODO: finalize threshold!
      final double score =
          total >= MIN_COMMITS ? (double) maxContributorCommitsByPath.get(file.path()) / total : 0d;
      scoreByFileRevisionId.put(file.fileRevisionId(), new MetricScore(score, score));
    }
    return scoreByFileRevisionId;
  }
}
