package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.FileSnapshot;

public class AbandonedKnowledgeSilo extends SocialMetric {

  @Override
  public String getId() {
    return "abandonedKnowledgeSilo";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {
    final MetricInput unfilteredInput =
        new MetricInput(
            input.base(),
            input.snapshot(),
            Set.of(), // empty contributor set so no filtering
            input.coreContributorIds(),
            input.repoTimeBounds(),
            input.mergedPrStats(),
            input.normalizationOpts());
    final KnowledgeSilo knowledgeSilo = new KnowledgeSilo();
    final KnowledgeStaleness knowledgeStaleness = new KnowledgeStaleness();
    final Map<Long, MetricScore> knowledgeSiloScores = knowledgeSilo.computeMetric(unfilteredInput);
    final Map<Long, MetricScore> knowledgeStalenessScores =
        knowledgeStaleness.computeMetric(unfilteredInput);

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (final FileSnapshot file : input.snapshot()) {
      final long fileId = file.fileRevisionId();
      final double score =
          knowledgeSiloScores.get(fileId).raw() * knowledgeStalenessScores.get(fileId).raw();
      scoreByFileRevisionId.put(fileId, new MetricScore(score, score));
    }
    return scoreByFileRevisionId;
  }
}
