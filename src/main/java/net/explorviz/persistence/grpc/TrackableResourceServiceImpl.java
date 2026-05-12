package net.explorviz.persistence.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.explorviz.persistence.ogm.AnnotationType;
import net.explorviz.persistence.ogm.Issue;
import net.explorviz.persistence.ogm.PullRequest;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.ogm.ResourceAnnotation;
import net.explorviz.persistence.ogm.ResourceState;
import net.explorviz.persistence.ogm.ResourceVersion;
import net.explorviz.persistence.ogm.TrackableResource;
import net.explorviz.persistence.proto.TrackableResourceEvent;
import net.explorviz.persistence.proto.TrackableResourceService;
import net.explorviz.persistence.repository.ApplicationRepository;
import net.explorviz.persistence.repository.BranchRepository;
import net.explorviz.persistence.repository.CommitRepository;
import net.explorviz.persistence.repository.ContributorRepository;
import net.explorviz.persistence.repository.FileRevisionRepository;
import net.explorviz.persistence.repository.LandscapeRepository;
import net.explorviz.persistence.repository.RepositoryRepository;
import net.explorviz.persistence.repository.TagRepository;
import net.explorviz.persistence.repository.TrackableResourceRepository;
import net.explorviz.persistence.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
@Blocking
public class TrackableResourceServiceImpl implements TrackableResourceService {

  private static final String NO_PARENT_ID = "NONE";
  @Inject ApplicationRepository applicationRepository;
  @Inject BranchRepository branchRepository;
  @Inject CommitRepository commitRepository;
  @Inject LandscapeRepository landscapeRepository;
  @Inject RepositoryRepository repositoryRepository;
  @Inject FileRevisionRepository fileRevisionRepository;
  @Inject TagRepository tagRepository;
  @Inject ContributorRepository contributorRepository;
  @Inject SessionFactory sessionFactory;
  @Inject TrackableResourceRepository trackableResourceRepository;

  @Override
  public Uni<Empty> persistTrackableResourceEvent(final TrackableResourceEvent request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      saveTrackableResourceEvent(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GGrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    }
  }

  public void saveTrackableResourceEvent(
      final Session session, final TrackableResourceEvent event) {
    final Repository repo =
        repositoryRepository
            .findRepositoryByNameAndLandscapeToken(
                session, event.getRepositoryName(), event.getLandscapeToken())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("No corresponding state data was sent before.")
                        .asRuntimeException());

    final Integer number = Integer.parseInt(event.getResourceId());

    // cleanly convert ProtoBuf Timestamp to Instant
    final Instant eventTime =
        Instant.ofEpochSecond(
            event.getEventTimestamp().getSeconds(), event.getEventTimestamp().getNanos());

    final ResourceVersion newResourceVersion = new ResourceVersion();
    newResourceVersion.setDescription(event.getDescription());
    newResourceVersion.setState(ResourceState.valueOf(event.getNewState().name()));
    newResourceVersion.setWebUrl(event.getWebUrl());
    newResourceVersion.setTitle(event.getTitle());
    newResourceVersion.setCreationDate(eventTime);
    final String[] labels = event.getLabels().split(",");
    final Set<String> labelSet = new HashSet<>(Arrays.asList(labels));
    newResourceVersion.setLabels(labelSet);

    final ResourceAnnotation newAnnotation =
        new ResourceAnnotation(
            eventTime,
            event.getAnnotationId(),
            AnnotationType.valueOf(event.getAnnotationType().name()));

    newAnnotation.setAssociatedContributor(
        contributorRepository.getOrCreateContributor(session, event.getActor()));

    final Class<? extends TrackableResource> type =
        switch (event.getResourceType()) {
          case ISSUE -> Issue.class;
          case PULL_REQUEST -> PullRequest.class;
          default ->
              throw new IllegalStateException("Unexpected value: " + event.getResourceType());
        };

    final TrackableResource resource;
    resource =
        trackableResourceRepository.getOrCreate(
            session, type, number, repo.getName(), event.getLandscapeToken());

    trackableResourceRepository.addAnnotationEvent(
        session, resource, newAnnotation, newResourceVersion);
    linkResourceToRepository(session, repo.getName(), event.getLandscapeToken(), resource);
  }

  private void linkResourceToRepository(
      final Session session,
      final String repoName,
      final String tokenId,
      final TrackableResource resource) {
    session.query(
        """
        MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(r:Repository {name: $repoName})
        MATCH (t:%s) WHERE id(t) = $resourceId
        MERGE (r)-[:CONTAINS]->(t)
        """
            .formatted(resource.getClass().getSimpleName()),
        Map.of(
            "tokenId", tokenId,
            "repoName", repoName,
            "resourceId", resource.getId()));
  }
}
