package net.explorviz.persistence.api.v3;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import net.explorviz.persistence.repository.RepositoryRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/** Contains endpoints concerning git repository analysis. */
@Path("/v3/landscapes/{landscapeToken}")
public class DebugResource {

  @Inject SessionFactory sessionFactory;

  @Inject RepositoryRepository repositoryRepository;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/debug-runs/{repositoryName}")
  public List<String> getDebugRuns(@RestPath final String landscapeToken, @RestPath final String repositoryName) {
    final Session session = sessionFactory.openSession();
    return DebugRunRepository.findDebugRunsForRepositoryAndLandscapeToken(session, repositoryName, landscapeToken);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/repositories/with-debug-runs")
  public List<DebugRunDto> getRepositoryNamesWithDebugRuns(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();
    return repositoryRepository.fetchAllRepositoryNamesWithDebugRunsInLandscape(session, landscapeToken);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/debug-snapshots/{debugRunId}")
  public List<DebugSnapshotDto> getDebugSnapshots(@RestPath final String debugRunId) {
    final Session session = sessionFactory.openSession();
    return DebugSnapshotRepository.findDebugSnapshotsForDebugRun(session, debugRunId);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/variables/{debugSnapshotId}")
  public List<VariableDto> getDebugSnapshots(@RestPath final String debugSnapshotId) {
    final Session session = sessionFactory.openSession();
    return VariableRepository.findVariablesForDebugSnapshot(session, debugSnapshotId);
  }

}
