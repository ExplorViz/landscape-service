package net.explorviz.landscape.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.explorviz.landscape.service.DatabasePurgeService;
import org.jboss.resteasy.reactive.RestQuery;

/**
 * Endpoints used by the code-analyzer benchmark mode to reset the database between runs.
 */
@Path("/api/benchmark")
public class BenchmarkResource {

  private static final int DEFAULT_CHUNK_SIZE = 10_000;

  @Inject DatabasePurgeService databasePurgeService;

  @POST
  @Path("/purge-database")
  @Produces(MediaType.TEXT_PLAIN)
  public Response purgeDatabase(@RestQuery final Integer chunkSize) {
    final int effectiveChunkSize =
        chunkSize != null && chunkSize > 0 ? chunkSize : DEFAULT_CHUNK_SIZE;
    final int deletedNodes = databasePurgeService.purgeDatabaseInChunks(effectiveChunkSize);
    return Response.ok("Deleted " + deletedNodes + " nodes in chunks of " + effectiveChunkSize)
        .build();
  }
}
