package net.explorviz.landscape.api.v3;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.explorviz.landscape.api.v3.model.ContributorsDto;
import net.explorviz.landscape.repository.SocialMetricsService;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@Path("/v3/landscapes/{landscapeToken}/contributors/{repositoryName}")
public class ContributorsResource {

  @Inject SocialMetricsService socialMetricsService;
  @Inject SessionFactory sessionFactory;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public ContributorsDto getContributors(
      @RestPath final String landscapeToken, @RestPath final String repositoryName) {
    final Session session = sessionFactory.openSession();

    return socialMetricsService.getContributorsDto(landscapeToken, repositoryName, session);
  }
}
