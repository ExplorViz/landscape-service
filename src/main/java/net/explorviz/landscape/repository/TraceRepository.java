package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Trace;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class TraceRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Trace> findTraceById(final Session session, final String traceId) {
    return Optional.ofNullable(
        session.queryForObject(
            Trace.class,
            "MATCH (t:Trace {traceId: $traceId}) RETURN t;",
            Map.of("traceId", traceId)));
  }

  /**
   * Find all traces in a landscape which contain any span within the given time range. The traces
   * are hydrated to include all their child spans. The spans are hydrated to include their parent
   * span and the function they represent.
   *
   * @param session OGM session object
   * @param landscapeToken String identifier of the visualization landscape
   * @param from Lower bound of time range to include (epoch nanosecond value)
   * @param to Upper bound of time range to include (epoch nanosecond value)
   * @return List of traces in the landscape within the time range, hydrated to include all
   *     contained spans and the functions they represent
   */
  public List<Trace> findHydratedTraces(
      final Session session, final String landscapeToken, final long from, final long to) {
    return Lists.newArrayList(
        session.query(
            Trace.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(t:Trace)
            WHERE EXISTS {
              MATCH (t)-[:CONTAINS]->(s:Span)
              WHERE s.startTime >= $from AND s.startTime <= $to
            }
            CALL apoc.path.subgraphAll(t, {
              relationshipFilter: "CONTAINS>|REPRESENTS>|HAS_PARENT>"
            })
            YIELD relationships
            UNWIND relationships as r
            RETURN startNode(r), r, endNode(r);
            """,
            Map.of("tokenId", landscapeToken, "from", from, "to", to)));
  }

  /**
   * Finds an associated commit hash for a trace by looking for any file referenced by the trace
   * that is contained in a commit. The query operates under the assumption that for any given
   * trace, there will be at most one such associated commit hash to be found, and therefore returns
   * the first match it discovers.
   *
   * @param session OGM session object
   * @param landscapeToken String identifier of the visualization landscape
   * @param traceId OpenTelemetry trace ID
   * @return Optional describing the commit hash string. Empty if no match is found
   */
  public Optional<String> findCommitHashForTrace(
      final Session session, final String landscapeToken, final String traceId) {
    return Optional.ofNullable(
        session.queryForObject(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(t:Trace {traceId: $traceId})
            MATCH (t)
              -[:CONTAINS]->(:Span)
              -[:REPRESENTS]->(:Function)
              <-[:CONTAINS]-(:FileRevision)
              <-[:CONTAINS]-(c:Commit)
            LIMIT 1
            RETURN c.hash;
            """,
            Map.of("tokenId", landscapeToken, "traceId", traceId)));
  }

  public List<Timestamp> findTimestampsForLandscapeTokenAndCommitAndTimeRange(
      final Session session,
      final String landscapeToken,
      final long newest,
      final long oldest,
      final String commitHash,
      final long bucketSize) {
    return session.queryDto(
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(t:Trace)
          -[:CONTAINS]->(s:Span)
        WHERE
          (s)-[:REPRESENTS]->(:Function)
            <-[:CONTAINS*]-(:FileRevision)
            <-[:CONTAINS]-(:Commit {hash: $commitHash}) AND
          s.startTime >= $oldest AND s.startTime <= $newest
        WITH
          s, toInteger(s.startTime / $bucketSize) * $bucketSize AS bucket
        RETURN bucket AS startTimeEpochNano, COUNT(s) AS spanCount
        ORDER BY bucket ASC;
        """,
        Map.of(
            "tokenId", landscapeToken,
            "newest", newest,
            "oldest", oldest,
            "commitHash", commitHash,
            "bucketSize", bucketSize),
        Timestamp.class);
  }

  public List<Timestamp> findTimestampsForLandscapeTokenAndTimeRange(
      final Session session,
      final String landscapeToken,
      final long newest,
      final long oldest,
      final long bucketSize) {
    return session.queryDto(
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(t:Trace)
          -[:CONTAINS]->(s:Span)
        WHERE
          s.startTime >= $oldest AND s.startTime <= $newest
        WITH
          s, toInteger(s.startTime / $bucketSize) * $bucketSize AS bucket
        RETURN bucket AS startTimeEpochNano, COUNT(s) AS spanCount
        ORDER BY bucket ASC;
        """,
        Map.of(
            "tokenId",
            landscapeToken,
            "newest",
            newest,
            "oldest",
            oldest,
            "bucketSize",
            bucketSize),
        Timestamp.class);
  }

  public void deleteTraceData(final Session session, final String landscapeToken) {
    session.query(
        """
        MATCH (:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(t:Trace)
          -[:CONTAINS]->(s:Span)
        OPTIONAL MATCH (s)
          -[:REPRESENTS]->(f:Function)
        DETACH DELETE t, s
        MATCH (fr:FileRevision)
          -[:CONTAINS]->(f)
        WHERE
          NOT (:Commit)-[:CONTAINS]->(fr)
        CALL apoc.path.subgraphAll(fr, {
          relationshipFilter: "CONTAINS>"
        })
        YIELD nodes
        UNWIND nodes as n
        DETACH DELETE n, f, fr
        OPTIONAL MATCH (d:Directory|Application)
        WHERE
         NOT (d)-[:CONTAINS|HAS_ROOT*]->(:FileRevision)
        DETACH DELETE d;
        """,
        Map.of("tokenId", landscapeToken));
  }

  public Trace getOrCreateTrace(final Session session, final String traceId) {
    return findTraceById(session, traceId).orElse(new Trace(traceId));
  }

  /**
   * Represents a collection of spans within a specific time range.
   *
   * @param startTimeEpochNano Start time of the time range, given in epoch nanoseconds
   * @param spanCount Number of spans in the time range
   */
  public record Timestamp(long startTimeEpochNano, long spanCount) {}

  /**
   * Represents aggregated communication between two files.
   *
   * @param sourceFileId ID of the source file
   * @param targetFileId ID of the target file
   * @param requestCount Number of requests between the files
   */
  public record FileCommunication(
      Long sourceFileId,
      String sourceFileName,
      Long targetFileId,
      String targetFileName,
      long requestCount,
      long functionCount,
      long executionTime) {}

  /**
   * Represents a function called between files and its request count.
   *
   * @param functionId ID of the called function
   * @param functionName Name of the called function
   * @param requestCount Number of requests to this function
   * @param executionTime Accumulated execution time of all spans for this function
   */
  public record FunctionCommunication(
      Long functionId,
      String functionName,
      Long sourceFileId,
      String sourceFileName,
      Long targetFileId,
      String targetFileName,
      long requestCount,
      long executionTime) {}

  /**
   * Finds aggregated communication between files for a given landscape and time range.
   *
   * @param session OGM session object
   * @param landscapeToken String identifier of the visualization landscape
   * @param from Lower bound of time range to include (epoch nanosecond value)
   * @param to Upper bound of time range to include (epoch nanosecond value)
   * @return List of aggregated file communications
   */
  public List<FileCommunication> findAggregatedFileCommunication(
      final Session session, final String landscapeToken, final long from, final long to) {
    return session.queryDto(
        """
        MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(t:Trace)-[:CONTAINS]->(child:Span)
        MATCH (child)-[:HAS_PARENT]->(parent:Span)
        WHERE child.startTime >= $from AND child.startTime <= $to
        MATCH (child)-[:REPRESENTS]->(childFunc:Function)<-[:CONTAINS*]-(childFile:FileRevision)
        MATCH (parent)-[:REPRESENTS]->(parentFunc:Function)<-[:CONTAINS*]-(parentFile:FileRevision)
        WITH parentFile, childFile, COUNT(child) AS requestCount, COUNT(DISTINCT childFunc) AS functionCount, SUM(child.endTime - child.startTime) AS executionTime
        RETURN id(parentFile) AS sourceFileId, parentFile.name AS sourceFileName, id(childFile) AS targetFileId, childFile.name AS targetFileName, requestCount, functionCount, executionTime;
        """,
        Map.of("tokenId", landscapeToken, "from", from, "to", to),
        FileCommunication.class);
  }

  /**
   * Finds unique functions called from a source file to a target file within a given time range.
   *
   * @param session OGM session object
   * @param landscapeToken String identifier of the visualization landscape
   * @param sourceFileId ID of the source file
   * @param targetFileId ID of the target file
   * @param from Lower bound of time range to include (epoch nanosecond value)
   * @param to Upper bound of time range to include (epoch nanosecond value)
   * @return List of unique functions called from source to target
   */
  public List<FunctionCommunication> findCalledFunctionsBetweenFiles(
      final Session session,
      final String landscapeToken,
      final long sourceFileId,
      final long targetFileId,
      final long from,
      final long to) {
    return session.queryDto(
        """
        MATCH (sourceNode) WHERE id(sourceNode) = $sourceFileId
        MATCH (targetNode) WHERE id(targetNode) = $targetFileId
        MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(t:Trace)-[:CONTAINS]->(child:Span)
        MATCH (child)-[:HAS_PARENT]->(parent:Span)
        WHERE child.startTime >= $from AND child.startTime <= $to
        MATCH (child)-[:REPRESENTS]->(childFunc:Function)<-[:CONTAINS*]-(childFile:FileRevision)
        MATCH (parent)-[:REPRESENTS]->(parentFunc:Function)<-[:CONTAINS*]-(parentFile:FileRevision)
        WHERE (sourceNode)-[:CONTAINS*0..]->(parentFile) AND (targetNode)-[:CONTAINS*0..]->(childFile)
        RETURN id(childFunc) AS functionId, childFunc.name AS functionName,
               id(parentFile) AS sourceFileId, parentFile.name AS sourceFileName,
               id(childFile) AS targetFileId, childFile.name AS targetFileName,
               COUNT(child) AS requestCount, SUM(child.endTime - child.startTime) AS executionTime;
        """,
        Map.of(
            "tokenId",
            landscapeToken,
            "sourceFileId",
            sourceFileId,
            "targetFileId",
            targetFileId,
            "from",
            from,
            "to",
            to),
        FunctionCommunication.class);
  }
}
