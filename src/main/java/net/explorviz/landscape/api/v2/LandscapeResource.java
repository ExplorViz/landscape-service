package net.explorviz.landscape.api.v2;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.explorviz.landscape.api.v2.model.TimestampDto;
import net.explorviz.landscape.api.v2.model.TraceDto;
import net.explorviz.landscape.api.v2.model.landscape.ApplicationDto;
import net.explorviz.landscape.api.v2.model.landscape.LandscapeDto;
import net.explorviz.landscape.api.v2.model.landscape.NodeDto;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Trace;
import net.explorviz.landscape.repository.ApplicationRepository;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.RepositoryRepository;
import net.explorviz.landscape.repository.TraceRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@Path("/v2/landscapes/{landscapeToken}")
public class LandscapeResource {

  @Inject SessionFactory sessionFactory;

  @Inject ApplicationRepository applicationRepository;

  @Inject CommitRepository commitRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Inject TraceRepository traceRepository;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/structure")
  public LandscapeDto getStructureData(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();

    final List<Application> ogmApps =
        applicationRepository.fetchAllApplicationsHydratedForRuntimeData(session, landscapeToken);

    final NodeDto node = new NodeDto("", "", ogmApps.stream().map(ApplicationDto::new).toList());
    return new LandscapeDto(landscapeToken, List.of(node), List.of());
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/dynamic")
  public List<TraceDto> getDynamicData(
      @RestPath final String landscapeToken, @RestQuery final Long from, @RestQuery final Long to) {

    final Session session = sessionFactory.openSession();

    final long fromTimestamp = Objects.requireNonNullElse(from, Long.MIN_VALUE);
    final long toTimestamp = Objects.requireNonNullElse(to, Long.MAX_VALUE);

    final List<Trace> ogmTraces =
        traceRepository.findHydratedTraces(session, landscapeToken, fromTimestamp, toTimestamp);

    return ogmTraces.stream()
        .filter(
            t -> {
              if (t.getStartTime() == null || t.getEndTime() == null) {
                Log.errorf("Trace missing start or end timestamp, ignoring: %s", t.getTraceId());
                return false;
              }
              return true;
            })
        .map(
            t -> {
              final Optional<String> commitHash =
                  traceRepository.findCommitHashForTrace(session, landscapeToken, t.getTraceId());
              return new TraceDto(t, landscapeToken, commitHash.orElse("UNKNOWN"));
            })
        .toList();
  }

  @GET
  @Path("/timestamps")
  @Produces(MediaType.APPLICATION_JSON)
  public Multi<TimestampDto> getTimestamps(
      @RestPath final String landscapeToken,
      @RestQuery final Long newest,
      @RestQuery final Long oldest,
      @RestQuery final String commit) {
    final Session session = sessionFactory.openSession();

    final long newestTimestamp = Objects.requireNonNullElse(newest, Long.MAX_VALUE);
    final long oldestTimestamp = Objects.requireNonNullElse(oldest, Long.MIN_VALUE);

    final List<TimestampDto> timestamps;

    final long defaultBucketSizeNano = 10_000_000_000L; // 10 seconds in nanoseconds

    if (commit != null) {
      timestamps =
          traceRepository
              .findTimestampsForLandscapeTokenAndCommitAndTimeRange(
                  session,
                  landscapeToken,
                  newestTimestamp,
                  oldestTimestamp,
                  commit,
                  defaultBucketSizeNano)
              .stream()
              .map(TimestampDto::new)
              .toList();
    } else {
      timestamps =
          traceRepository
              .findTimestampsForLandscapeTokenAndTimeRange(
                  session, landscapeToken, newestTimestamp, oldestTimestamp, defaultBucketSizeNano)
              .stream()
              .map(TimestampDto::new)
              .toList();
    }

    return Multi.createFrom().iterable(timestamps);
  }
}
