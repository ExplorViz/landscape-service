package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.proto.ContributorData;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class ContributorRepository {

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

    if (isBlank(contributor.getGithubLogin()) && isUsable(data.getGithubLogin())) {
      contributor.setGithubLogin(data.getGithubLogin());
      updated = true;
    }

    if (isBlank(contributor.getAvatarUrl()) && isUsable(data.getAvatarUrl())) {
      contributor.setAvatarUrl(data.getAvatarUrl());
      updated = true;
    }

    if (isBlank(contributor.getGitUsername()) && isUsable(data.getGitUsername())) {
      contributor.setGitUsername(data.getGitUsername());
      updated = true;
    }

    if (isBlank(contributor.getEmail()) && isUsable(data.getEmail())) {
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

  private boolean isUsable(final String str) {
    return isPresent(str) && !"unknown".equals(str);
  }
}
