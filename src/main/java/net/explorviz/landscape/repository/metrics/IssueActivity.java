package net.explorviz.landscape.repository.metrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.FileSnapshot;
import net.explorviz.landscape.util.MetricNormalizer;

public class IssueActivity extends SocialMetric {

  @Override
  public String getId() {
    return "issueActivity";
  }

  @Override
  public Map<Long, MetricScore> computeMetric(final MetricInput input) {
    final List<FileSnapshot> snapshot = input.snapshot();
    final double[] bdRaw = new double[snapshot.size()];
    for (int i = 0; i < snapshot.size(); i++) {
      final FileSnapshot file = snapshot.get(i);
      final long issueCount = input.issueCountByPath().getOrDefault(file.path(), 0L);
      bdRaw[i] = file.loc() > 0 ? issueCount / file.loc() : 0;
    }

    final MetricNormalizer normalizer = new MetricNormalizer(bdRaw, input.normalizationOpts());

    final Map<Long, MetricScore> scoreByFileRevisionId = new LinkedHashMap<>();
    for (int i = 0; i < snapshot.size(); i++) {
      final FileSnapshot file = snapshot.get(i);
      scoreByFileRevisionId.put(
          file.fileRevisionId(), new MetricScore(bdRaw[i], normalizer.normalize(bdRaw[i])));
    }
    return scoreByFileRevisionId;
  }
}
