package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.proto.ContributorData;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class ContributorRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Contributor> findContributorWithMostCommits(
      final Session session, final String repoName) {
    return Optional.ofNullable(
        session.queryForObject(
            Contributor.class,
            """
            MATCH (c:Contributor)-[:AUTHORED]->(commit:Commit)-[:IN_BRANCH]->(b:Branch)
              WHERE b.name = $repoName
              RETURN c, count(commit) AS commitCount
              ORDER BY commitCount DESC
              LIMIT 1;
            """,
            Map.of("repoName", repoName)));
  }

  public Map<String, Long> countCommitsPerContributor(
      final Session session, final String repoName) {
    final List<Map<String, Object>> results =
        Lists.newArrayList(
            session.query(
                """
                MATCH (c:Contributor)-[:AUTHORED]->(commit:Commit)-[:IN_BRANCH]->(b:Branch)
                RETURN c.gitUsername AS name, count(DISTINCT commit) AS commitCount
                """,
                Map.of("repoName", repoName)));

    return results.stream()
        .collect(
            Collectors.toMap(
                row -> (String) row.get("name"), row -> (Long) row.get("commitCount")));
  }

  public Optional<Contributor> findContributor(
      final Session session, final String fieldName, final String fieldValue) {
    if (fieldName == null || fieldName.isEmpty() || fieldValue == null || fieldValue.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        session.queryForObject(
            Contributor.class,
            """
            MATCH (c:Contributor)
            WHERE c.%s = $fieldValue
            RETURN c
            LIMIT 1;
            """
                .formatted(fieldName),
            Map.of("fieldValue", fieldValue)));
  }

  public Contributor getOrCreateContributor(final Session session, final ContributorData data) {
    final Contributor contributor =
        findExistingContributor(session, data)
            .orElseGet(
                () ->
                    new Contributor(
                        data.getGitUsername(),
                        data.getEmail(),
                        data.getGithubLogin(),
                        data.getAvatarUrl()));

    final boolean isUpdated = updateContributorFields(contributor, data);

    if (contributor.getId() == null || isUpdated) {
      session.save(contributor);
    }
    return contributor;
  }

  public Optional<Contributor> findExistingContributor(
      final Session session, final ContributorData data) {
    Optional<Contributor> contributor = findContributor(session, "email", data.getEmail());

    if (contributor.isEmpty()
        && isPresent(data.getGithubLogin())
        && !"unknown".equals(data.getGithubLogin())) {
      contributor = findContributor(session, "githubLogin", data.getGithubLogin());
    }
    if (contributor.isEmpty()
        && isPresent(data.getGitUsername())
        && !"unknown".equals(data.getGitUsername())) {
      contributor = findContributor(session, "gitUsername", data.getGitUsername());
    }
    return contributor;
  }

  private boolean updateContributorFields(
      final Contributor contributor, final ContributorData data) {
    boolean updated = false;

    if (isBlank(contributor.getGithubLogin())) {
      contributor.setGithubLogin(data.getGithubLogin());
      updated = true;
    }

    if (isBlank(contributor.getAvatarUrl())) {
      contributor.setAvatarUrl(data.getAvatarUrl());
      updated = true;
    }

    if (isBlank(contributor.getGitUsername())) {
      contributor.setGitUsername(data.getGitUsername());
      updated = true;
    }

    if (isBlank(contributor.getEmail())) {
      contributor.setEmail(data.getEmail());
      updated = true;
    }

    return updated;
  }

  private boolean isPresent(final String str) {
    return str != null && !str.isBlank();
  }

  private boolean isBlank(final String str) {
    return str == null || str.isBlank();
  }
}
