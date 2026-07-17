package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.explorviz.landscape.util.CoreContributorHelper;
import net.explorviz.landscape.util.N95Normalizer;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class SocialMetricsService {

  @Inject SocialMetricsRepository socialMetricsRepository;
  @Inject ContributorRepository contributorRepository;

  private enum MetricId {
    COMMIT_ACTIVITY("commitActivity"),
    CORE_CONTRIBUTOR_ACTIVITY("coreContributorActivity"),
    KNOWLEDGE_SILO("knowledgeSilo"),
    KNOWLEDGE_STALENESS("knowledgeStaleness");

    private final String metricId;

    MetricId(final String metricId) {
      this.metricId = metricId;
    }
  }

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

    final Map<Long, MetricScore> ca = computeCommitActivity(base, snapshot, contributorIds);

    final List<SocialMetricDto> result = new ArrayList<>(snapshot.size());
    for (final FileSnapshot file : snapshot) {
      final Map<String, MetricScore> metrics = new LinkedHashMap<>();

      metrics.put(MetricId.COMMIT_ACTIVITY.metricId, ca.get(file.fileRevisionId()));
      // add more metrics
      result.add(new SocialMetricDto(file.fileRevisionId(), file.path(), metrics));
    }

    return result;
  }

  public static Map<Long, MetricScore> computeCommitActivity(
      final List<ContributorFileActivity> base,
      final List<FileSnapshot> snapshot,
      final Set<Long> contributorIds) {

    // precalculate CA Map needed for normalization
    final Map<String, Long> caByPath = getCaByPath(base, contributorIds);

    // filter for files actually contained in the currently viewed commit
    final double[] filtered = new double[snapshot.size()];
    for (int i = 0; i < snapshot.size(); i++) {
      filtered[i] = caByPath.getOrDefault(snapshot.get(i).path(), 0L);
    }

    final N95Normalizer normalizer = new N95Normalizer(filtered);

    final Map<Long, MetricScore> files = new LinkedHashMap<>();
    for (int i = 0; i < snapshot.size(); i++) {
      final FileSnapshot f = snapshot.get(i);
      files.put(
          f.fileRevisionId(), new MetricScore(filtered[i], normalizer.normalize(filtered[i])));
    }
    return files;
  }

  static Map<String, Long> getCaByPath(
      final List<ContributorFileActivity> base, final Set<Long> contributorIds) {
    final Map<String, Long> result = new HashMap<>();
    for (final ContributorFileActivity row : base) {
      if (contributorIds.isEmpty() || contributorIds.contains(row.contributorId())) {
        result.merge(row.path(), row.commits(), Long::sum);
      }
    }
    return result;
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
