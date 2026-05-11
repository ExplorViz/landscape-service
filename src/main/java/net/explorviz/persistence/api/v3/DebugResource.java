package net.explorviz.persistence.api.v3;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import net.explorviz.persistence.api.v3.model.DebugRunDto;
import net.explorviz.persistence.api.v3.model.DebugSnapshotDto;
import net.explorviz.persistence.api.v3.model.VariableDto;
import net.explorviz.persistence.ogm.DebugSnapshot;
import net.explorviz.persistence.repository.DebugRunRepository;
import net.explorviz.persistence.repository.DebugSnapshotRepository;
import net.explorviz.persistence.repository.RepositoryRepository;
import net.explorviz.persistence.repository.VariableRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/** Contains endpoints concerning git repository analysis. */
@Path("/v3/landscapes/{landscapeToken}")
public class DebugResource {

  @Inject SessionFactory sessionFactory;

  @Inject RepositoryRepository repositoryRepository;

  @Inject
  DebugRunRepository debugRunRepository;

  @Inject
  DebugSnapshotRepository debugSnapshotRepository;

  @Inject
  VariableRepository variableRepository;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/debug-runs/{repositoryName}")
  public List<DebugRunDto> getDebugRuns(@RestPath final String landscapeToken, @RestPath final String repositoryName) {
    final Session session = sessionFactory.openSession();
    return debugRunRepository
        .findDebugRunsForRepositoryAndLandscapeToken(session, repositoryName, landscapeToken)
        .stream()
        .map(data -> new DebugRunDto(
            data.getId().toString(),
            data.getStatus(),
            data.getNumOfSnapshots(),
            data.getStartTime(),
            data.getEndTime()))
        .toList();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/repositories/with-debug-runs")
  public List<String> getRepositoryNamesWithDebugRuns(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();
    return repositoryRepository.fetchAllRepositoryNamesWithDebugRunsInLandscape(session, landscapeToken);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/debug-snapshots/{debugRunId}")
  public List<DebugSnapshotDto> getDebugSnapshots(@RestPath final String debugRunId) {
    final Session session = sessionFactory.openSession();
    return debugSnapshotRepository
        .findDebugSnapshotsForDebugRun(session, debugRunId)
        .stream()
        .map(data -> new DebugSnapshotDto(
            data.getId().toString(),
            data.getLineOfBreakpoint(),
            data.getNumOfVariables(),
            data.getTimestamp()))
        .toList();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/variables/{debugSnapshotId}")
  public List<VariableDto> getVariables(@RestPath final String debugSnapshotId) {
    final Session session = sessionFactory.openSession();
    return variableRepository
        .findVariablesForDebugSnapshot(session, debugSnapshotId)
        .stream()
        .map(data -> new VariableDto(
            data.getId().toString(),
            data.getName(),
            data.getType(),
            data.getValue(),
            data.getMetrics()))
        .toList();
  }





}
