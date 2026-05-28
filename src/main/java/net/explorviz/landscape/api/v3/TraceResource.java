package net.explorviz.landscape.api.v3;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.explorviz.landscape.api.v3.model.AggregatedEntityCommunicationDto;
import net.explorviz.landscape.api.v3.model.CommunicationDto;
import net.explorviz.landscape.api.v3.model.EntityPairCommunicationDto;
import net.explorviz.landscape.api.v3.model.MetricSummaryDto;
import net.explorviz.landscape.api.v3.model.SimpleFunctionDto;
import net.explorviz.landscape.api.v3.model.trace.TimestampDto;
import net.explorviz.landscape.api.v3.model.trace.TraceDto;
import net.explorviz.landscape.ogm.Trace;
import net.explorviz.landscape.repository.TraceRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/**
 * Contains endpoints concerning dynamic data, i.e. communication data retrieved from runtime
 * analysis of traces.
 */
@Path("/v3/landscapes/{landscapeToken}")
public class TraceResource {
  private static final String REQUEST_COUNT = "requestCount";
  private static final String FUNCTION_COUNT = "functionCount";
  private static final String EXECUTION_TIME = "executionTime";

  @Inject SessionFactory sessionFactory;

  @Inject TraceRepository traceRepository;

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
        .map(t -> new TraceDto(t, landscapeToken))
        .toList();
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/file-communication")
  public AggregatedEntityCommunicationDto getAggregatedFileCommunication(
      @RestPath final String landscapeToken, @RestQuery final Long from, @RestQuery final Long to) {

    final Session session = sessionFactory.openSession();

    final long fromTimestamp = Objects.requireNonNullElse(from, Long.MIN_VALUE);
    final long toTimestamp = Objects.requireNonNullElse(to, Long.MAX_VALUE);

    final List<TraceRepository.FileCommunication> rawCommunications =
        traceRepository.findAggregatedFileCommunication(
            session, landscapeToken, fromTimestamp, toTimestamp);

    final Map<String, CommunicationDto> mergedCommunications = new HashMap<>();

    for (final TraceRepository.FileCommunication c : rawCommunications) {
      final String sourceId = c.sourceFileId().toString();
      final String targetId = c.targetFileId().toString();

      // Create a consistent key for merging (e.g., sorted IDs)
      final String mergeKey =
          c.sourceFileId() <= c.targetFileId()
              ? sourceId + "-" + targetId
              : targetId + "-" + sourceId;

      mergedCommunications.compute(
          mergeKey,
          (key, existing) -> {
            if (existing == null) {
              return new CommunicationDto(
                  key,
                  c.sourceFileName() + " - " + c.targetFileName(),
                  sourceId,
                  targetId,
                  false,
                  Map.of(
                      REQUEST_COUNT, (double) c.requestCount(),
                      FUNCTION_COUNT, (double) c.functionCount(),
                      EXECUTION_TIME, (double) c.executionTime()));
            } else {
              final double totalRequestCount =
                  existing.metrics().get(REQUEST_COUNT).doubleValue() + c.requestCount();
              final double totalFunctionCount =
                  existing.metrics().get(FUNCTION_COUNT).doubleValue() + c.functionCount();
              final double totalExecutionTime =
                  existing.metrics().get(EXECUTION_TIME).doubleValue() + c.executionTime();
              return new CommunicationDto(
                  key,
                  existing.name(),
                  existing.sourceBuildingId(),
                  existing.targetBuildingId(),
                  true,
                  Map.of(
                      REQUEST_COUNT, totalRequestCount,
                      FUNCTION_COUNT, totalFunctionCount,
                      EXECUTION_TIME, totalExecutionTime));
            }
          });
    }

    final List<CommunicationDto> communications = List.copyOf(mergedCommunications.values());

    double minRequest = Double.MAX_VALUE;
    double maxRequest = Double.MIN_VALUE;
    double minFunction = Double.MAX_VALUE;
    double maxFunction = Double.MIN_VALUE;
    double minExecution = Double.MAX_VALUE;
    double maxExecution = Double.MIN_VALUE;

    for (final CommunicationDto edge : communications) {
      final double reqCount = edge.metrics().get(REQUEST_COUNT).doubleValue();
      minRequest = Math.min(minRequest, reqCount);
      maxRequest = Math.max(maxRequest, reqCount);

      final double funcCount = edge.metrics().get(FUNCTION_COUNT).doubleValue();
      minFunction = Math.min(minFunction, funcCount);
      maxFunction = Math.max(maxFunction, funcCount);

      final double execTime = edge.metrics().get(EXECUTION_TIME).doubleValue();
      minExecution = Math.min(minExecution, execTime);
      maxExecution = Math.max(maxExecution, execTime);
    }

    if (communications.isEmpty()) {
      minRequest = 0;
      maxRequest = 0;
      minFunction = 0;
      maxFunction = 0;
      minExecution = 0;
      maxExecution = 0;
    }

    return new AggregatedEntityCommunicationDto(
        Map.of(
            REQUEST_COUNT, new MetricSummaryDto(minRequest, maxRequest),
            FUNCTION_COUNT, new MetricSummaryDto(minFunction, maxFunction),
            EXECUTION_TIME, new MetricSummaryDto(minExecution, maxExecution)),
        communications);
  }

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/communication/{sourceEntityId}/{targetEntityId}")
  public List<EntityPairCommunicationDto> getCalledFunctionsBetweenFiles(
      @RestPath final String landscapeToken,
      @RestPath final Long sourceEntityId,
      @RestPath final Long targetEntityId,
      @RestQuery final Long from,
      @RestQuery final Long to) {

    final Session session = sessionFactory.openSession();

    final long fromTimestamp = Objects.requireNonNullElse(from, Long.MIN_VALUE);
    final long toTimestamp = Objects.requireNonNullElse(to, Long.MAX_VALUE);

    final List<TraceRepository.FunctionCommunication> forwardFunctions =
        traceRepository.findCalledFunctionsBetweenFiles(
            session, landscapeToken, sourceEntityId, targetEntityId, fromTimestamp, toTimestamp);

    final List<TraceRepository.FunctionCommunication> backwardFunctions =
        traceRepository.findCalledFunctionsBetweenFiles(
            session, landscapeToken, targetEntityId, sourceEntityId, fromTimestamp, toTimestamp);

    final Map<String, FilePairData> pairDataMap = new HashMap<>();

    forwardFunctions.forEach(
        f -> {
          final String key =
              Math.min(f.sourceFileId(), f.targetFileId())
                  + "-"
                  + Math.max(f.sourceFileId(), f.targetFileId());
          final FilePairData data = pairDataMap.computeIfAbsent(key, k -> new FilePairData());
          if (data.srcId == null) {
            data.srcId = f.sourceFileId();
            data.srcName = f.sourceFileName();
            data.tgtId = f.targetFileId();
            data.tgtName = f.targetFileName();
          }
          data.forward = true;

          final boolean isForward = f.sourceFileId().equals(data.srcId);
          data.functions.add(
              new SimpleFunctionDto(
                  f.functionId().toString(),
                  f.functionName(),
                  isForward,
                  f.requestCount(),
                  f.executionTime()));
        });

    backwardFunctions.forEach(
        f -> {
          final String key =
              Math.min(f.sourceFileId(), f.targetFileId())
                  + "-"
                  + Math.max(f.sourceFileId(), f.targetFileId());
          final FilePairData data = pairDataMap.computeIfAbsent(key, k -> new FilePairData());
          if (data.srcId == null) {
            data.srcId = f.sourceFileId();
            data.srcName = f.sourceFileName();
            data.tgtId = f.targetFileId();
            data.tgtName = f.targetFileName();
          }
          data.backward = true;

          final boolean isForward = f.sourceFileId().equals(data.srcId);
          data.functions.add(
              new SimpleFunctionDto(
                  f.functionId().toString(),
                  f.functionName(),
                  isForward,
                  f.requestCount(),
                  f.executionTime()));
        });

    return pairDataMap.values().stream()
        .map(
            data ->
                new EntityPairCommunicationDto(
                    data.srcId,
                    data.srcName,
                    data.tgtId,
                    data.tgtName,
                    data.forward && data.backward,
                    data.functions))
        .toList();
  }

  private static final class FilePairData {
    Long srcId;
    String srcName;
    Long tgtId;
    String tgtName;
    boolean forward;
    boolean backward;
    final List<SimpleFunctionDto> functions = new ArrayList<>();
  }

  @GET
  @Path("/timestamps")
  @Produces(MediaType.APPLICATION_JSON)
  public List<TimestampDto> getTimestamps(
      @RestPath final String landscapeToken,
      @RestQuery final Long newest,
      @RestQuery final Long oldest,
      @RestQuery final String commit,
      @RestQuery final Long size) {

    final Session session = sessionFactory.openSession();

    final long newestTimestamp = Objects.requireNonNullElse(newest, Long.MAX_VALUE);
    final long oldestTimestamp = Objects.requireNonNullElse(oldest, Long.MIN_VALUE);

    final long defaultBucketSizeNano = 10_000_000_000L; // 10 seconds in nanoseconds

    final long bucketSize;
    if (size == null || size <= 0) {
      bucketSize = defaultBucketSizeNano;
    } else {
      bucketSize = size * 1_000_000L; // Milliseconds to nanoseconds
    }

    final List<TimestampDto> timestamps;

    if (commit != null) {
      timestamps =
          traceRepository
              .findTimestampsForLandscapeTokenAndCommitAndTimeRange(
                  session, landscapeToken, newestTimestamp, oldestTimestamp, commit, bucketSize)
              .stream()
              .map(TimestampDto::new)
              .toList();
    } else {
      timestamps =
          traceRepository
              .findTimestampsForLandscapeTokenAndTimeRange(
                  session, landscapeToken, newestTimestamp, oldestTimestamp, bucketSize)
              .stream()
              .map(TimestampDto::new)
              .toList();
    }

    return timestamps;
  }

  /**
   * Debug function to delete all data gathered from runtime analysis in the landscape.
   *
   * @param landscapeToken String identifier of the visualization landscape
   */
  @DELETE
  @Path("/trace-data")
  public void deleteTraceData(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();
    traceRepository.deleteTraceData(session, landscapeToken);
  }
}
