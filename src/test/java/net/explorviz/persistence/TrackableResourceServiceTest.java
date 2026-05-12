package net.explorviz.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.Timestamp;
import io.quarkus.grpc.GrpcClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import net.explorviz.persistence.ogm.Contributor;
import net.explorviz.persistence.ogm.ResourceVersion;
import net.explorviz.persistence.ogm.TrackableResource;
import net.explorviz.persistence.proto.AnnotationType;
import net.explorviz.persistence.proto.ContributorData;
import net.explorviz.persistence.proto.ResourceState;
import net.explorviz.persistence.proto.StateDataRequest;
import net.explorviz.persistence.proto.StateDataService;
import net.explorviz.persistence.proto.TrackableResourceEvent;
import net.explorviz.persistence.proto.TrackableResourceService;
import net.explorviz.persistence.proto.TrackableResourceType;
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
  void testPersistTrackableResource() {
    Instant now = Instant.now();
    Timestamp protoTimestamp =
        Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();

    ContributorData c1 = ContributorData.newBuilder().setGitUsername("c1").setEmail("c1@").build();

    ContributorData c2 = ContributorData.newBuilder().setGitUsername("c2").setEmail("c2@").build();

    ContributorData c3 = ContributorData.newBuilder().setGitUsername("c3").setEmail("c3@").build();

    TrackableResourceEvent event1 =
        TrackableResourceEvent.newBuilder()
            .setEventTimestamp(protoTimestamp)
            .setActor(c1)
            .setAnnotationId("external annot. id 1")
            .setLandscapeToken(this.landscapeToken)
            .setRepositoryName(this.repoName)
            .setResourceId("14")
            .setResourceType(TrackableResourceType.ISSUE)
            .setAnnotationType(AnnotationType.CREATE)
            .setNewState(ResourceState.OPEN)
            .setTitle("Issue #14")
            .setDescription("EXAMPLE DESCCCC")
            .setLabels("BUG,XYZ,LOW_PRIO,HIGH_PRIO")
            .build();

    TrackableResourceEvent event2 =
        TrackableResourceEvent.newBuilder()
            .setEventTimestamp(protoTimestamp)
            .setActor(c2)
            .setAnnotationId("external annot. id 2")
            .setLandscapeToken(this.landscapeToken)
            .setRepositoryName(this.repoName)
            .setResourceId("15")
            .setResourceType(TrackableResourceType.ISSUE)
            .setAnnotationType(AnnotationType.CREATE)
            .setNewState(ResourceState.OPEN)
            .setTitle("Issue #15")
            .setDescription("Another issue description")
            .setLabels("ENHANCEMENT,COSMETIC")
            .build();

    TrackableResourceEvent event3 =
        TrackableResourceEvent.newBuilder()
            .setEventTimestamp(protoTimestamp)
            .setActor(c3)
            .setAnnotationId("external annot. id 3")
            .setLandscapeToken(this.landscapeToken)
            .setRepositoryName(this.repoName)
            .setResourceId("1")
            .setResourceType(TrackableResourceType.PULL_REQUEST)
            .setAnnotationType(AnnotationType.CREATE)
            .setNewState(ResourceState.OPEN)
            .setTitle("PR #1")
            .setDescription("Initial PR")
            .setLabels("CORE")
            .build();

    // adding another event to existing issue number 15
    TrackableResourceEvent event4 =
        TrackableResourceEvent.newBuilder()
            .setEventTimestamp(protoTimestamp)
            .setActor(c3)
            .setAnnotationId("external annot. id 4")
            .setLandscapeToken(this.landscapeToken)
            .setRepositoryName(this.repoName)
            .setResourceId("15")
            .setResourceType(TrackableResourceType.ISSUE)
            .setAnnotationType(AnnotationType.LABEL)
            .setNewState(ResourceState.OPEN)
            .setTitle("Issue #15")
            .setDescription("Another issue description")
            .setLabels("ENHANCEMENT,COSMETIC,HIGH_PRIO")
            .build();

    trackableResourceService
        .persistTrackableResourceEvent(event1)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    trackableResourceService
        .persistTrackableResourceEvent(event2)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    trackableResourceService
        .persistTrackableResourceEvent(event3)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    trackableResourceService
        .persistTrackableResourceEvent(event4)
        .await()
        .atMost(Duration.ofSeconds(GRPC_AWAIT_SECONDS));

    Iterable<TrackableResource> resources =
        session.query(
            TrackableResource.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(tr:TrackableResource)
            RETURN tr
            """,
            Map.of("landscapeToken", landscapeToken, "repoName", repoName));

    int count = 0;
    for (TrackableResource resource : resources) {
      count++;
    }
    assertEquals(3, count, "Expected 3 trackable resources to be persisted in the database.");
    Iterator<TrackableResource> it = resources.iterator();

    Iterable<Contributor> contributors =
        session.query(
            Contributor.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(:TrackableResource)-[:HAS_VERSION]->(:ResourceVersion)-[:CREATED_BY]->(c:Contributor)
            RETURN DISTINCT c
            """,
            Map.of("landscapeToken", landscapeToken, "repoName", repoName));

    int contributorCount = 0;
    for (Contributor resource : contributors) {
      contributorCount++;
    }
    assertEquals(3, contributorCount, "Expected 3 contributors to be persisted in the database.");

    Iterable<ResourceVersion> issue15versions =
        session.query(
            ResourceVersion.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(tr:TrackableResource {number: 15})-[:HAS_VERSION]->(rv:ResourceVersion)
            RETURN rv
            """,
            Map.of("landscapeToken", landscapeToken, "repoName", repoName));

    int issue15count = 0;
    for (ResourceVersion resource : issue15versions) {
      issue15count++;
    }
    assertEquals(
        2, issue15count, "Expected 2 versions of Issue 15 to be persisted in the database.");
  }
}
