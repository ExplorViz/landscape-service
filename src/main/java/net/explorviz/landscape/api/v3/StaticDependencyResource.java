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

  private static final String PARAM_LANDSCAPE_TOKEN = "landscapeToken";
  private static final String PARAM_COMMIT_HASH = "commitHash";

  @Inject StructureRepository structureRepository;
  @Inject SessionFactory sessionFactory;

  @GET
  @Path("/{landscapeToken}/staticDependencies/imports")
  public AggregatedEntityCommunicationDto getImportDependencies(
      @PathParam(PARAM_LANDSCAPE_TOKEN) final String landscapeToken,
      @QueryParam(PARAM_COMMIT_HASH) final String commitHash) {

    final Session session = sessionFactory.openSession();

    final List<StructureRepository.StaticDependency> rawDependencies =
        structureRepository.fetchImportDependencies(session, landscapeToken, commitHash);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final StructureRepository.StaticDependency dep : rawDependencies) {
      final String sourceId = dep.sourceClazzId().toString();
      final String targetId = dep.targetClazzId().toString();
      final String mergeKey = sourceId + "-" + targetId;

      mergedCommunications.put(
          mergeKey,
          new CommunicationDto(mergeKey, dep.type(), sourceId, targetId, false, Map.of()));
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    return new AggregatedEntityCommunicationDto(Map.of(), communications);
  }

  @GET
  @Path("/{landscapeToken}/staticDependencies/extends")
  public AggregatedEntityCommunicationDto getExtendsDependencies(
      @PathParam(PARAM_LANDSCAPE_TOKEN) final String landscapeToken,
      @QueryParam(PARAM_COMMIT_HASH) final String commitHash) {

    final Session session = sessionFactory.openSession();

    final List<StructureRepository.StaticDependency> rawDependencies =
        structureRepository.fetchExtendsDependencies(session, landscapeToken, commitHash);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final StructureRepository.StaticDependency dep : rawDependencies) {
      final String sourceId = dep.sourceClazzId().toString();
      final String targetId = dep.targetClazzId().toString();
      final String mergeKey = sourceId + "-" + targetId;

      mergedCommunications.put(
          mergeKey,
          new CommunicationDto(mergeKey, dep.type(), sourceId, targetId, false, Map.of()));
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    return new AggregatedEntityCommunicationDto(Map.of(), communications);
  }

  @GET
  @Path("/{landscapeToken}/staticDependencies/implements")
  public AggregatedEntityCommunicationDto getImplementsDependencies(
      @PathParam(PARAM_LANDSCAPE_TOKEN) final String landscapeToken,
      @QueryParam(PARAM_COMMIT_HASH) final String commitHash) {

    final Session session = sessionFactory.openSession();

    final List<StructureRepository.StaticDependency> rawDependencies =
        structureRepository.fetchImplementsDependencies(session, landscapeToken, commitHash);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final StructureRepository.StaticDependency dep : rawDependencies) {
      final String sourceId = dep.sourceClazzId().toString();
      final String targetId = dep.targetClazzId().toString();
      final String mergeKey = sourceId + "-" + targetId;

      mergedCommunications.put(
          mergeKey,
          new CommunicationDto(mergeKey, dep.type(), sourceId, targetId, false, Map.of()));
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    return new AggregatedEntityCommunicationDto(Map.of(), communications);
  }

  @GET
  @Path("/{landscapeToken}/staticDependencies/methodCalls")
  public AggregatedEntityCommunicationDto getMethodCallDependencies(
      @PathParam(PARAM_LANDSCAPE_TOKEN) final String landscapeToken,
      @QueryParam(PARAM_COMMIT_HASH) final String commitHash) {

    final Session session = sessionFactory.openSession();

    final List<StructureRepository.StaticDependency> rawDependencies =
        structureRepository.fetchMethodCallDependencies(session, landscapeToken, commitHash);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final StructureRepository.StaticDependency dep : rawDependencies) {
      final String sourceId = dep.sourceClazzId().toString();
      final String targetId = dep.targetClazzId().toString();
      final String mergeKey = sourceId + "-" + targetId;

      mergedCommunications.put(
          mergeKey,
          new CommunicationDto(mergeKey, dep.type(), sourceId, targetId, false, Map.of()));
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    return new AggregatedEntityCommunicationDto(Map.of(), communications);
  }

  @GET
  @Path("/{landscapeToken}/staticDependencies/fieldTypeUsages")
  public AggregatedEntityCommunicationDto getFieldTypeUsageDependencies(
      @PathParam(PARAM_LANDSCAPE_TOKEN) final String landscapeToken,
      @QueryParam(PARAM_COMMIT_HASH) final String commitHash) {

    final Session session = sessionFactory.openSession();

    final List<StructureRepository.StaticDependency> rawDependencies =
        structureRepository.fetchFieldTypeUsageDependencies(session, landscapeToken, commitHash);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final StructureRepository.StaticDependency dep : rawDependencies) {
      final String sourceId = dep.sourceClazzId().toString();
      final String targetId = dep.targetClazzId().toString();
      final String mergeKey = sourceId + "-" + targetId;

      mergedCommunications.put(
          mergeKey,
          new CommunicationDto(mergeKey, dep.type(), sourceId, targetId, false, Map.of()));
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    return new AggregatedEntityCommunicationDto(Map.of(), communications);
  }
}
