package net.explorviz.landscape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.protobuf.Timestamp;
import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.explorviz.landscape.ogm.ResourceVersion;
import net.explorviz.landscape.proto.AnnotationType;
import net.explorviz.landscape.proto.ContributorData;
import net.explorviz.landscape.proto.ResourceState;
import net.explorviz.landscape.proto.StateDataRequest;
import net.explorviz.landscape.proto.StateDataService;
import net.explorviz.landscape.proto.TrackableResourceEvent;
import net.explorviz.landscape.proto.TrackableResourceService;
import net.explorviz.landscape.proto.TrackableResourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
public class TrackableResourceServiceTest {

  private static final long GRPC_AWAIT_SECONDS = 5;

  @GrpcClient TrackableResourceService trackableResourceService;

  @GrpcClient StateDataService stateDataService;

  @Inject SessionFactory sessionFactory;

  private Session session;
  private String landscapeToken;
  private String repoName;
  private String branchName;

  @BeforeEach
  void init() {
    session = sessionFactory.openSession();
    session.purgeDatabase();

    landscapeToken = "mytokenvalue";
    repoName = "myrepo";
    branchName = "main";
  }

  private void initializeRepository() {
    StateDataRequest stateDataRequest =
        StateDataRequest.newBuilder()
            .setLandscapeToken(landscapeToken)
            .setRepositoryName(repoName)
            .setBranchName(branchName)
            .build();

    stateDataService
        .getStateData(stateDataRequest)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));
  }

  @Test
  void shouldPersistNewIssueEvent() {
    initializeRepository();
    TrackableResourceEvent event =
        createBaseEventBuilder("14", TrackableResourceType.ISSUE)
            .setAnnotationType(AnnotationType.CREATE)
            .setNewState(ResourceState.OPEN)
            .setTitle("Issue #14")
            .build();

    trackableResourceService
        .persistTrackableResourceEvent(event)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    long count = session.queryForObject(Long.class, "MATCH (tr:Issue) RETURN count(tr)", Map.of());
    assertEquals(1, count);
  }

  @Test
  void shouldPersistNewPullRequestEvent() {
    initializeRepository();
    TrackableResourceEvent event =
        createBaseEventBuilder("1", TrackableResourceType.PULL_REQUEST)
            .setAnnotationType(AnnotationType.CREATE)
            .setNewState(ResourceState.OPEN)
            .setTitle("PR #1")
            .build();

    trackableResourceService
        .persistTrackableResourceEvent(event)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    long count =
        session.queryForObject(Long.class, "MATCH (tr:PullRequest) RETURN count(tr)", Map.of());
    assertEquals(1, count);
  }

  @Test
  void shouldAppendNewVersionToExistingResource() {
    initializeRepository();
    String resourceId = "15";

    // Create
    trackableResourceService
        .persistTrackableResourceEvent(
            createBaseEventBuilder(resourceId, TrackableResourceType.ISSUE)
                .setAnnotationType(AnnotationType.CREATE)
                .setNewState(ResourceState.OPEN)
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    // Update (Label)
    trackableResourceService
        .persistTrackableResourceEvent(
            createBaseEventBuilder(resourceId, TrackableResourceType.ISSUE)
                .setAnnotationType(AnnotationType.LABEL)
                .setNewState(ResourceState.OPEN)
                .build())
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Iterable<ResourceVersion> versions =
        session.query(
            ResourceVersion.class,
            "MATCH (tr:TrackableResource {number: 15})-[:HAS_VERSION]->(rv:ResourceVersion) RETURN"
                + " rv",
            Map.of());

    int count = 0;
    for (ResourceVersion ignored : versions) {
      count++;
    }
    assertEquals(2, count);
  }

  @Test
  void shouldFailIfRepositoryNotInitialized() {
    TrackableResourceEvent event =
        createBaseEventBuilder("99", TrackableResourceType.ISSUE)
            .setAnnotationType(AnnotationType.CREATE)
            .setNewState(ResourceState.OPEN)
            .build();

    assertThrows(
        StatusRuntimeException.class,
        () ->
            trackableResourceService
                .persistTrackableResourceEvent(event)
                .await()
                .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS)));
  }

  private TrackableResourceEvent.Builder createBaseEventBuilder(
      String resourceId, TrackableResourceType type) {
    Instant now = Instant.now();
    Timestamp protoTimestamp =
        Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();

    ContributorData actor = ContributorData.newBuilder().setGitUsername("tester").build();

    return TrackableResourceEvent.newBuilder()
        .setEventTimestamp(protoTimestamp)
        .setActor(actor)
        .setAnnotationId("ext-" + resourceId + "-" + type)
        .setLandscapeToken(this.landscapeToken)
        .setRepositoryName(this.repoName)
        .setResourceId(resourceId)
        .setResourceType(type);
  }
}
