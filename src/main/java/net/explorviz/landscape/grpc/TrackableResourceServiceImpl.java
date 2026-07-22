package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import net.explorviz.landscape.ogm.AnnotationType;
import net.explorviz.landscape.ogm.Issue;
import net.explorviz.landscape.ogm.PullRequest;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.ogm.ResourceAnnotation;
import net.explorviz.landscape.ogm.ResourceState;
import net.explorviz.landscape.ogm.ResourceVersion;
import net.explorviz.landscape.ogm.TrackableResource;
import net.explorviz.landscape.proto.RelinkResourcesRequest;
import net.explorviz.landscape.proto.TrackableResourceEvent;
import net.explorviz.landscape.proto.TrackableResourceService;
import net.explorviz.landscape.proto.TrackableResourceType;
import net.explorviz.landscape.repository.ContributorRepository;
import net.explorviz.landscape.repository.RepositoryRepository;
import net.explorviz.landscape.repository.TrackableResourceRepository;
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class TrackableResourceServiceImpl implements TrackableResourceService {

  @Inject RepositoryRepository repositoryRepository;
  @Inject ContributorRepository contributorRepository;
  @Inject SessionFactory sessionFactory;
  @Inject TrackableResourceRepository trackableResourceRepository;

  @Override
  @Blocking
  public Uni<Empty> persistTrackableResourceEvent(final TrackableResourceEvent request) {
    final Session session = sessionFactory.openSession();
    try (Transaction tx = session.beginTransaction()) {
      saveTrackableResourceEvent(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    } finally {
      session.clear();
    }
  }

  @Override
  @Blocking
  public Uni<Empty> relinkResources(final RelinkResourcesRequest request) {
    final Session session = sessionFactory.openSession();
    try (Transaction tx = session.beginTransaction()) {
      trackableResourceRepository.linkPullRequestToCommits(
          session, request.getLandscapeToken(), request.getRepositoryName());
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    } finally {
      session.clear();
    }
  }

  public void saveTrackableResourceEvent(
      final Session session, final TrackableResourceEvent event) {

    // skip if annotation already persisted
    if (trackableResourceRepository
        .findAnnotationByExternalId(session, event.getAnnotationId())
        .isPresent()) {
      return;
    }

    final Repository repo = getRepository(session, event);
    final ResourceVersion resourceVersion = populateResourceVersion(event);
    final ResourceAnnotation resourceAnnotation = populateResourceAnnotation(session, event);
    final TrackableResource resource = getTrackableResource(session, event);

    trackableResourceRepository.addAnnotationEvent(
        session, resource, resourceAnnotation, resourceVersion);

    linkResourceToRepository(session, repo.getName(), event.getLandscapeToken(), resource);
    if (event.getResourceType() == TrackableResourceType.PULL_REQUEST
        && event.getAnnotationType() == net.explorviz.landscape.proto.AnnotationType.CREATE) {
      linkPullRequestResources(session, event, resource);
    }
  }

  private Repository getRepository(final Session session, final TrackableResourceEvent event) {
    return repositoryRepository
        .findRepositoryByNameAndLandscapeToken(
            session, event.getRepositoryName(), event.getLandscapeToken())
        .orElseThrow(
            () ->
                Status.FAILED_PRECONDITION
                    .withDescription("No corresponding state data was sent before.")
                    .asRuntimeException());
  }

  private ResourceVersion populateResourceVersion(final TrackableResourceEvent event) {
    final ResourceVersion resourceVersion = new ResourceVersion();
    resourceVersion.setDescription(event.getDescription());
    resourceVersion.setState(ResourceState.valueOf(event.getNewState().name()));
    resourceVersion.setWebUrl(event.getWebUrl());
    resourceVersion.setTitle(event.getTitle());
    resourceVersion.setCreationDate(getEventDate(event));
    resourceVersion.setExternalId(event.getAnnotationId());
    resourceVersion.setLabels(new HashSet<>(event.getLabelsList()));
    return resourceVersion;
  }

  private ResourceAnnotation populateResourceAnnotation(
      final Session session, final TrackableResourceEvent event) {
    final ResourceAnnotation annotation =
        new ResourceAnnotation(
            getEventDate(event),
            event.getAnnotationId(),
            AnnotationType.valueOf(event.getAnnotationType().name()));

    if (!"unknown".equals(event.getActor().getGithubLogin())) {
      annotation.setAssociatedContributor(
          contributorRepository.getOrCreateContributor(session, event.getActor()));
    }

    return annotation;
  }

  private TrackableResource getTrackableResource(
      final Session session, final TrackableResourceEvent event) {
    final Class<? extends TrackableResource> type =
        switch (event.getResourceType()) {
          case ISSUE -> Issue.class;
          case PULL_REQUEST -> PullRequest.class;
          default ->
              throw new IllegalStateException("Unexpected value: " + event.getResourceType());
        };

    final TrackableResource resource;
    resource =
        trackableResourceRepository.getOrCreateTrackableResource(
            session,
            type,
            Integer.parseInt(event.getResourceId()),
            event.getRepositoryName(),
            event.getLandscapeToken());
    return resource;
  }

  private Instant getEventDate(final TrackableResourceEvent event) {
    return Instant.ofEpochSecond(
        event.getEventTimestamp().getSeconds(), event.getEventTimestamp().getNanos());
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

  private void linkPullRequestResources(
      final Session session, final TrackableResourceEvent event, final TrackableResource resource) {
    final PullRequest pullRequest = (PullRequest) resource;
    pullRequest.setCommitHashes(new HashSet<>(event.getCommitShasList()));
    session.save(pullRequest, 0);

    trackableResourceRepository.linkPullRequestToIssues(
        session,
        event.getLandscapeToken(),
        event.getRepositoryName(),
        pullRequest.getId(),
        event.getReferencedIssueNumbersList());
  }
}
