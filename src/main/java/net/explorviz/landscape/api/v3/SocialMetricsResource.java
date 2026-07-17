package net.explorviz.landscape.api.v3;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.explorviz.landscape.api.v3.model.SocialMetricDto;
import net.explorviz.landscape.repository.SocialMetricsService;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@Path("/v3/landscapes/{landscapeToken}/social-metrics/{repositoryName}")
public class SocialMetricsResource {

  @Inject SessionFactory sessionFactory;

  @Inject SocialMetricsService socialMetricsService;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public List<SocialMetricDto> getSocialMetrics(
      @RestPath final String landscapeToken,
      @RestPath final String repositoryName,
      @RestQuery final String commit,
      @RestQuery final Long from,
      @RestQuery final Long to,
      @RestQuery final List<Long> contributors) {

    if (commit == null || commit.isBlank()) {
      throw new BadRequestException("commit cannot be null or blank");
    }

    final Session session = sessionFactory.openSession();

    final long fromTimestamp = Objects.requireNonNullElse(from, Long.MIN_VALUE);
    final long toTimestamp = Objects.requireNonNullElse(to, Long.MAX_VALUE);
    final Set<Long> contributorIds = contributors == null ? Set.of() : Set.copyOf(contributors);

    return socialMetricsService.calculateMetrics(
        session,
        landscapeToken,
        repositoryName,
        fromTimestamp,
        toTimestamp,
        contributorIds,
        commit);
  }
}
