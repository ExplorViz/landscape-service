package net.explorviz.persistence.grpc;

import com.google.protobuf.Empty;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.explorviz.persistence.proto.DebugSnapshotData;
import net.explorviz.persistence.proto.DebugSnapshotService;
import net.explorviz.persistence.util.GrpcExceptionMapper;
import org.jboss.logging.Logger;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class DebugSnapshotServiceImpl implements DebugSnapshotService {

  private static final Logger LOG = Logger.getLogger(DebugSnapshotServiceImpl.class);

  @Inject SessionFactory sessionFactory;

  @Blocking
  @Override
  public Uni<Empty> saveCurrentState(final DebugSnapshotData request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      logReceivedDebugSnapshot(request);
      saveDebugSnapshot(session, request);
      tx.commit();

      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      LOG.error("Saving debug snapshot failed", e);
      return Uni.createFrom()
          .failure(GrpcExceptionMapper.mapToGrpcException(e, request.getLandscapeToken()));
    }
  }

  public void saveDebugSnapshot(final Session session, final DebugSnapshotData request) {
    final String landscapeToken = request.getLandscapeToken();
    final String repositoryName = request.getRepositoryName();
    final String commitHash = request.getCommitHash();
    final String debugRunId = request.getDebugRunId();
    final long epochNano = request.getEpochNano();

    final String debugRunKey = repositoryName + "::" + debugRunId;
    final String debugSnapshotKey = debugRunKey + "::snapshot::" + epochNano;

    final List<Map<String, Object>> variables = flattenVariables(request, debugSnapshotKey);

    session.query(
        """
        MATCH (landscape:Landscape {tokenId: $landscapeToken})
        MATCH (landscape)-[:CONTAINS]->(repository:Repository {name: $repositoryName})

        MERGE (commit:Commit {hash: $commitHash})
        MERGE (repository)-[:CONTAINS]->(commit)

        MERGE (repository)-[:HAS_DEBUG_RUN]->(debugRun:DebugRun {debugRunKey: $debugRunKey})
        MERGE (debugRun)-[:RUNS_ON]->(commit)

        MERGE (debugSnapshot:DebugSnapshot {debugSnapshotKey: $debugSnapshotKey})
        SET debugSnapshot.timestamp = $epochNano
        MERGE (debugRun)-[:CONTAINS]->(debugSnapshot)

        WITH debugSnapshot, commit

        UNWIND $variables AS variableData
        MERGE (variable:Variable {variableKey: variableData.variableKey})
        SET variable.name = variableData.name,
            variable.value = variableData.value,
            variable.type = variableData.type,
            variable.instanceId = variableData.objectReference
        MERGE (debugSnapshot)-[:CAPTURES]->(variable)

        WITH variable, variableData, commit
        MATCH (commit)-[:CONTAINS]->(file:FileRevision)
        WHERE file.name = variableData.fileName
          AND file.packageName = variableData.packageName
        MERGE (variable)-[:MARKED_IN]->(file)
        """,
        Map.of(
            "landscapeToken", landscapeToken,
            "repositoryName", repositoryName,
            "commitHash", commitHash,
            "debugRunId", debugRunId,
            "debugRunKey", debugRunKey,
            "debugSnapshotKey", debugSnapshotKey,
            "epochNano", epochNano,
            "variables", variables));
  }

  private List<Map<String, Object>> flattenVariables(
      final DebugSnapshotData request, final String debugSnapshotKey) {

    final List<Map<String, Object>> flattenedVariables = new ArrayList<>();

    request
        .getVariablesList()
        .forEach(
            entry -> {
              entry
                  .getOwnerGroup()
                  .getValuesList()
                  .forEach(
                      runtimeValue -> {
                        final String variableKey =
                            debugSnapshotKey
                                + "::"
                                + entry.getId()
                                + "::"
                                + runtimeValue.getObjectReference();

                        flattenedVariables.add(
                            Map.ofEntries(
                                Map.entry("variableKey", variableKey),
                                Map.entry("name", entry.getName()),
                                Map.entry("definitionUri", entry.getDefinitionUri()),
                                Map.entry("sourcePath", entry.getSourcePath()),
                                Map.entry("fileName", entry.getFileName()),
                                Map.entry("packageName", entry.getPackageName()),
                                Map.entry("className", entry.getClassName()),
                                Map.entry("value", runtimeValue.getValue()),
                                Map.entry("type", runtimeValue.getType()),
                                Map.entry("objectReference", runtimeValue.getObjectReference()),
                                Map.entry("matchConfidence", runtimeValue.getMatchConfidence()),
                                Map.entry("runtimePath", runtimeValue.getRuntimePath())));
                      });
            });

    return flattenedVariables;
  }

  private void logReceivedDebugSnapshot(final DebugSnapshotData request) {
    LOG.infof(
        "Received debug snapshot: landscapeToken=%s, debugRunId=%s, repositoryName=%s,"
            + " commitHash=%s, epochNano=%d, variables=%d",
        request.getLandscapeToken(),
        request.getDebugRunId(),
        request.getRepositoryName(),
        request.getCommitHash(),
        request.getEpochNano(),
        request.getVariablesCount());

    request
        .getVariablesList()
        .forEach(
            variable -> {
              LOG.infof(
                  "VariableSnapshotEntry: id=%s, name=%s, definitionUri=%s,"
                      + " sourcePath=%s, fileName=%s, packageName=%s, className=%s,"
                      + " ownerType=%s, values=%d",
                  variable.getId(),
                  variable.getName(),
                  variable.getDefinitionUri(),
                  variable.getSourcePath(),
                  variable.getFileName(),
                  variable.getPackageName(),
                  variable.getClassName(),
                  variable.getOwnerGroup().getOwnerType(),
                  variable.getOwnerGroup().getValuesCount());

              variable
                  .getOwnerGroup()
                  .getValuesList()
                  .forEach(
                      value ->
                          LOG.infof(
                              "  RuntimeVariableValue: value=%s, type=%s, objectReference=%s,"
                                  + " matchConfidence=%s, runtimePath=%s",
                              value.getValue(),
                              value.getType(),
                              value.getObjectReference(),
                              value.getMatchConfidence(),
                              value.getRuntimePath()));
            });
  }
}
