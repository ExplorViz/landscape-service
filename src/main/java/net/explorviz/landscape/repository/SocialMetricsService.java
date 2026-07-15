package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.explorviz.landscape.util.N95Normalizer;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class SocialMetricsService {

  @Inject SocialMetricsRepository repository;

  private static final Map<String, Long> CABYPATH = new HashMap<>();

  private record TimeFrame(Long t1, Long t2) {}

  public record FileScore(
      Long fileRevisionId, String filePath, double rawScore, double normalizedScore) {}

  public void calculateMetrics(
      final Session session,
      final String token,
      final String repo,
      final Long from,
      final Long to,
      final Set<Long> contributorIds,
      final String commit) {
    final List<ContributorFileActivity> base =
        repository.getBaseAggregation(session, token, repo, from, to);
    final List<FileSnapshot> snapshot = repository.getFileSnapshots(session, token, repo, commit);
    CABYPATH.clear();

    // final List<FileScore> fileScoresCa =
    computeCommitActivity(base, snapshot, contributorIds);
  }

  public static List<FileScore> computeCommitActivity(
      final List<ContributorFileActivity> base,
      final List<FileSnapshot> snapshot,
      final Set<Long> contributorIds) {

    // precalculate CA Map needed for normalization
    fillCaMap(base, contributorIds);

    // filter for files actually contained in the currently viewed commit
    final double[] filtered = new double[snapshot.size()];
    for (int i = 0; i < snapshot.size(); i++) {
      filtered[i] = CABYPATH.getOrDefault(snapshot.get(i).path(), 0L);
    }

    final N95Normalizer normalizer = new N95Normalizer(filtered);

    final List<FileScore> files = new ArrayList<>(snapshot.size());
    for (int i = 0; i < snapshot.size(); i++) {
      final FileSnapshot f = snapshot.get(i);
      files.add(
          new FileScore(
              f.fileRevisionId(), f.path(), filtered[i], normalizer.normalize(filtered[i])));
    }
    return files;
  }

  // CA_raw(f)
  //  private long caRaw(final String filePath) {
  //    return caByPath.get(filePath);
  //  }

  static void fillCaMap(final List<ContributorFileActivity> base, final Set<Long> contributorIds) {
    for (final ContributorFileActivity row : base) {
      if (contributorIds.isEmpty() || contributorIds.contains(row.contributorId())) {
        CABYPATH.merge(row.path(), row.commits(), Long::sum);
      }
    }
  }
}
