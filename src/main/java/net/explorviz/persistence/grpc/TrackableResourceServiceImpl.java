package net.explorviz.persistence.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
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

    final TrackableResource resource;

    final ResourceVersion newResourceVersion = new ResourceVersion();
    newResourceVersion.setDescription(event.getDescription());
    newResourceVersion.setState(ResourceState.valueOf(event.getNewState().name()));
    newResourceVersion.setWebUrl(event.getWebUrl());
    final ResourceAnnotation newAnnotation =
        new ResourceAnnotation(
            eventTime,
            event.getAnnotationId(),
            AnnotationType.valueOf(event.getAnnotationType().name()));
    newAnnotation.setAssociatedContributor(
        contributorRepository.getOrCreateContributor(session, event.getActor()));

    switch (event.getResourceType()) {
      case ISSUE -> {
        resource =
            trackableResourceRepository.getOrCreate(
                session, Issue.class, number, repo.getName(), event.getLandscapeToken());
        //        addAnnotationAndVersion(session, resource, event);
        resource.setState(event.getNewState().name());
        trackableResourceRepository.addAnnotationEvent(
            session, resource, newAnnotation, newResourceVersion);
        repo.addIssue((Issue) resource);
        session.save(repo);
      }
      case PULL_REQUEST -> {
        resource =
            trackableResourceRepository.getOrCreate(
                session, PullRequest.class, number, repo.getName(), event.getLandscapeToken());
        //        addAnnotationAndVersion(session, resource, event);
        trackableResourceRepository.addAnnotationEvent(
            session, resource, newAnnotation, newResourceVersion);
        repo.addPullRequest((PullRequest) resource);
        session.save(repo);
      }
      default -> throw new IllegalStateException("Unexpected value: " + event.getResourceType());
    }
  }

  @Deprecated
  public void addAnnotationAndVersion(
      final Session session, final TrackableResource resource, final TrackableResourceEvent event) {
    trackableResourceRepository.addAnnotationAndVersion(
        session,
        resource.getClass(),
        resource.getNumber(),
        event.getRepositoryName(),
        event.getLandscapeToken(),
        event.getAnnotationId(),
        // TODO: TEST THIS!
        Instant.parse(
            event.getEventTimestamp().getSeconds()
                + "."
                + event.getEventTimestamp().getNanos()
                + "Z"),
        AnnotationType.valueOf(event.getAnnotationType().name()),
        contributorRepository.getOrCreateContributor(session, event.getActor()),
        new ResourceVersion());
  }
}
