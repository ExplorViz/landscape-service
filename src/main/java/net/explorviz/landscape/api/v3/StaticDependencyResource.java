package net.explorviz.landscape.api.v3;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.api.v3.model.AggregatedEntityCommunicationDto;
import net.explorviz.landscape.api.v3.model.CommunicationDto;
import net.explorviz.landscape.repository.StructureRepository;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@Path("/v3/landscapes")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class StaticDependencyResource {

  @Inject StructureRepository structureRepository;
  @Inject SessionFactory sessionFactory;

  @GET
  @Path("/{landscapeToken}/staticDependencies")
  public AggregatedEntityCommunicationDto getStaticDependencies(
      @PathParam("landscapeToken") final String landscapeToken,
      @QueryParam("commitHash") final String commitHash) {

    final Session session = sessionFactory.openSession();

    final List<StructureRepository.StaticDependency> rawDependencies =
        structureRepository.fetchStaticDependencies(session, landscapeToken, commitHash);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final StructureRepository.StaticDependency dep : rawDependencies) {
      final String sourceId = dep.sourceClazzId().toString();
      final String targetId = dep.targetClazzId().toString();
      final String mergeKey = sourceId + "-" + targetId + "-" + dep.type();

      mergedCommunications.put(
          mergeKey,
          new CommunicationDto(mergeKey, dep.type(), sourceId, targetId, false, Map.of()));
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    return new AggregatedEntityCommunicationDto(Map.of(), communications);
  }
}
