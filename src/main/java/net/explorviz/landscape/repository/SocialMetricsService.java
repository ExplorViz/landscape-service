package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.ContributorsDto;
import net.explorviz.landscape.api.v3.model.ContributorsDto.ContributorDto;
import net.explorviz.landscape.api.v3.model.ContributorsDto.TimeRange;
import net.explorviz.landscape.api.v3.model.SocialMetricDto;
import net.explorviz.landscape.api.v3.model.SocialMetricDto.MetricScore;
import net.explorviz.landscape.repository.ContributorRepository.ContributorActivity;
import net.explorviz.landscape.repository.metrics.CommitActivity;
import net.explorviz.landscape.repository.metrics.CoreContributorActivity;
import net.explorviz.landscape.repository.metrics.KnowledgeSilo;
import net.explorviz.landscape.repository.metrics.SocialMetric;
import net.explorviz.landscape.repository.metrics.SocialMetric.MetricInput;
import net.explorviz.landscape.util.CoreContributorHelper;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class SocialMetricsService {

  @Inject SocialMetricsRepository socialMetricsRepository;
  @Inject ContributorRepository contributorRepository;

  private final List<SocialMetric> metrics =
      List.of(new CommitActivity(), new CoreContributorActivity(), new KnowledgeSilo());

  //      new KnowledgeSilo(),
  //      new KnowledgeStalenesas()

  public List<SocialMetricDto> calculateMetrics(
      final Session session,
      final String token,
      final String repo,
      final Long from,
      final Long to,
      final Set<Long> contributorIds,
      final String commit) {
    final List<ContributorFileActivity> base =
        socialMetricsRepository.getBaseAggregation(session, token, repo, from, to);
    final List<FileSnapshot> snapshot =
        socialMetricsRepository.getFileSnapshots(session, token, repo, commit);
    final List<ContributorActivity> contributorActivities =
        contributorRepository.getContributorData(session, token, repo);
    final Set<Long> coreIds =
        CoreContributorHelper.computeCoreContributorIds(contributorActivities);
    final MetricInput metricInput = new MetricInput(base, snapshot, contributorIds, coreIds);

    final Map<String, Map<Long, MetricScore>> fileScoresByMetricId = new LinkedHashMap<>();
    for (final SocialMetric metric : metrics) {
      fileScoresByMetricId.put(metric.getId(), metric.computeMetric(metricInput));
    }

    final List<SocialMetricDto> fileMetricDtos = new ArrayList<>(snapshot.size());
    for (final FileSnapshot file : snapshot) {
      final Map<String, MetricScore> scoresByMetricId = new LinkedHashMap<>();
      for (final Map.Entry<String, Map<Long, MetricScore>> entry :
          fileScoresByMetricId.entrySet()) {
        scoresByMetricId.put(entry.getKey(), entry.getValue().get(file.fileRevisionId()));
      }
      fileMetricDtos.add(new SocialMetricDto(file.fileRevisionId(), file.path(), scoresByMetricId));
    }
    return fileMetricDtos;
  }

  public ContributorsDto getContributorsDto(
      final String token, final String repo, final Session session) {
    final List<ContributorActivity> rows =
        contributorRepository.getContributorData(session, token, repo);

    if (rows.isEmpty()) {
      return new ContributorsDto(List.of(), new TimeRange(0L, 0L));
    }

    final Set<Long> coreIds = CoreContributorHelper.computeCoreContributorIds(rows);

    final List<ContributorDto> contributorDtos =
        rows.stream()
            .map(
                row ->
                    new ContributorDto(
                        row.contributorId(),
                        row.gitUsername(),
                        row.githubLogin(),
                        row.email(),
                        row.avatarUrl(),
                        (int) row.commitCount(),
                        coreIds.contains(row.contributorId())))
            .toList();

    long minDate = Long.MAX_VALUE;
    long maxDate = Long.MIN_VALUE;
    for (final ContributorActivity row : rows) {
      minDate = Math.min(minDate, row.minDate());
      maxDate = Math.max(maxDate, row.maxDate());
    }

    return new ContributorsDto(contributorDtos, new TimeRange(minDate, maxDate));
  }
}
