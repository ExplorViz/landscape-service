package net.explorviz.persistence.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.explorviz.persistence.ogm.Contributor;
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

  public Optional<Contributor> findAnyContributor(final Session session, final String repoName) {
    return Optional.ofNullable(
        session.queryForObject(
            Contributor.class,
            """
            MATCH (c:Contributor)-[:AUTHORED]->(commit:Commit)-[:IN_BRANCH]->(b:Branch)
              WHERE b.name = $repoName
              RETURN c
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
                RETURN c.name AS name, count(DISTINCT commit) AS commitCount
                """,
                Map.of("repoName", repoName)));

    return results.stream()
        .collect(
            Collectors.toMap(
                row -> (String) row.get("name"), row -> (Long) row.get("commitCount")));
  }

  public Contributor getOrCreateContributor(
      final Session session, final net.explorviz.persistence.proto.ContributorData data) {
    return findContributorByEmail(session, data.getEmail())
        .orElseGet(
            () ->
                new Contributor(
                    data.getGitUsername(),
                    data.getEmail(),
                    data.getGithubLogin(),
                    data.getAvatarUrl()));
  }

  public Optional<Contributor> findContributorByEmail(final Session session, final String email) {
    if (email == null || email.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(
        session.queryForObject(
            Contributor.class,
            """
            MATCH (c:Contributor)
            WHERE c.email = $email
            RETURN c
            LIMIT 1;
            """,
            Map.of("email", email)));
  }
}
