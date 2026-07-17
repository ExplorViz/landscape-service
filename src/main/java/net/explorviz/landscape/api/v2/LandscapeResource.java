package net.explorviz.landscape.api.v2;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import net.explorviz.landscape.api.v2.model.landscape.ApplicationDto;
import net.explorviz.landscape.api.v2.model.landscape.LandscapeDto;
import net.explorviz.landscape.api.v2.model.landscape.NodeDto;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.repository.ApplicationRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@Path("/v2/landscapes/{landscapeToken}")
public class LandscapeResource {

  @Inject SessionFactory sessionFactory;

  @Inject ApplicationRepository applicationRepository;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/structure")
  public LandscapeDto getStructureData(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();

    final List<Application> ogmApps =
        applicationRepository.fetchAllApplicationsHydratedForRuntimeData(session, landscapeToken);

    final NodeDto node = new NodeDto("", "", ogmApps.stream().map(ApplicationDto::new).toList());
    return new LandscapeDto(landscapeToken, List.of(node), List.of());
  }
}
