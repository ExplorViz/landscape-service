package net.explorviz.landscape.api.v3;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import net.explorviz.landscape.api.v3.model.CommitComparison;
import net.explorviz.landscape.api.v3.model.EvolutionStructureBatchRequest;
import net.explorviz.landscape.api.v3.model.FileDetailedDto;
import net.explorviz.landscape.api.v3.model.RepositoryEvolutionSelectionDto;
import net.explorviz.landscape.api.v3.model.landscape.FlatLandscapeDto;
import net.explorviz.landscape.repository.FileDetailedMapper;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.StructureRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/** Contains endpoints concerning landscape structure data, i.e. the shape of the landscape. */
@Path("/v3/landscapes/{landscapeToken}/structure")
public class StructureResource {

  @Inject SessionFactory sessionFactory;

  @Inject StructureRepository structureRepository;
  @Inject FileDetailedMapper fileDetailedMapper;
  @Inject FileRevisionRepository fileRevisionRepository;

  /** Retrieve all structure data gathered from runtime analysis. */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/runtime")
  public FlatLandscapeDto getRuntimeStructureData(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();

    return structureRepository.fetchFlatLandscapeForRuntimeData(session, landscapeToken);
  }

  /**
   * Retrieve structure data gathered from static analysis for a particular application and commit.
   *
   * @param landscapeToken String identifier of the landscape
   * @param repositoryName Name of the repository for which to retrieve structure data
   * @param commitHash Identifier of the git commit for which to retrieve structure
   * @return The flat landscape containing the applications of the repository at the given commit,
   *     where each application represents a city
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/evolution/{repositoryName}/{commitHash}")
  public FlatLandscapeDto getStaticStructureData(
      @RestPath final String landscapeToken,
      @RestPath final String repositoryName,
      @RestPath final String commitHash) {
    final Session session = sessionFactory.openSession();

    return structureRepository.fetchFlatLandscapeForStaticData(
        session,
        new StructureRepository.StaticDataRequest(landscapeToken, repositoryName, commitHash));
  }

  /**
   * Retrieve union of structure data for the two provided commits within the given repository. The
   * {@link CommitComparison} value is set relative to the second commit, e.g. "DELETED" is written
   * if some component is present in the first commit, but not the second.
   *
   * @param landscapeToken String identifier of the landscape
   * @param repositoryName Name of the repository for which to retrieve structure data
   * @param firstCommitHash Identifier of the first git commit against which to compare the second
   * @param secondCommitHash Identifier of the second git commit for which to retrieve structure.
   *     All {@link CommitComparison} values are given relative to this commit
   * @return A flat landscape containing the union of the applications in the specified repository
   */
  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/evolution/{repositoryName}/{firstCommitHash}-{secondCommitHash}")
  public FlatLandscapeDto getCombinedStaticStructureData(
      @RestPath final String landscapeToken,
      @RestPath final String repositoryName,
      @RestPath final String firstCommitHash,
      @RestPath final String secondCommitHash) {
    final Session session = sessionFactory.openSession();

    return structureRepository.fetchCombinedFlatLandscape(
        session,
        new StructureRepository.CombinedStaticDataRequest(
            landscapeToken, repositoryName, firstCommitHash, secondCommitHash));
  }

  /**
   * Retrieves static structure for multiple repositories in one request. Each list entry names a
   * repository and supplies either one commit hash or two hashes (baseline then target, identical
   * to {@link #getCombinedStaticStructureData}). Results are merged into a single flat landscape.
   */
  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/evolution/batch")
  public FlatLandscapeDto postEvolutionStructureBatch(
      @RestPath final String landscapeToken, final EvolutionStructureBatchRequest request) {

    final List<RepositoryEvolutionSelectionDto> repositories =
        EvolutionStructureBatchValidator.validatedSelections(request);

    final Session session = sessionFactory.openSession();

    return structureRepository.fetchFlatLandscapeForEvolutionBatch(
        session, landscapeToken, repositories);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/evolution/file-revision/{id}")
  public FileDetailedDto getFileDetailsById(
      @RestPath final String landscapeToken,
      @RestPath final Long id,
      @QueryParam("commitHash") final String commitHash) {
    final Session session = sessionFactory.openSession();

    return fileRevisionRepository
        .findFileDetailedContext(session, landscapeToken, id)
        .map(context -> fileDetailedMapper.map(context, commitHash))
        .orElseThrow(() -> new jakarta.ws.rs.NotFoundException("File revision not found"));
  }
}
