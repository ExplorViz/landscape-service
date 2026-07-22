package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import java.util.List;
import net.explorviz.landscape.proto.FileData;
import net.explorviz.landscape.proto.FileDataService;
import net.explorviz.landscape.repository.CommitMetricsAccumulator;
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class FileDataServiceImpl implements FileDataService {

  @Inject SessionFactory sessionFactory;
  @Inject FileDataBatchWriter fileDataBatchWriter;
  @Inject CommitMetricsAccumulator commitMetricsAccumulator;

  @Blocking
  @Override
  public Uni<Empty> persistFile(final FileData request) {
    return persistBatch(List.of(request));
  }

  /**
   * Client-streaming variant: accepts a stream of {@link FileData} messages for an entire commit,
   * collects them, and persists the whole set in a single Neo4j transaction using batched UNWIND
   * Cypher queries.
   */
  @Override
  public Uni<Empty> persistFiles(final Multi<FileData> request) {
    return request
        .collect()
        .asList()
        .emitOn(Infrastructure.getDefaultWorkerPool())
        .onItem()
        .transformToUni(this::persistBatch);
  }

  private Uni<Empty> persistBatch(final List<FileData> files) {
    final Session session = sessionFactory.openSession();
    String landscapeToken = null;
    String repoName = null;
    try (Transaction tx = session.beginTransaction()) {
      fileDataBatchWriter.persistBatch(session, files);
      if (!files.isEmpty()) {
        landscapeToken = files.get(0).getLandscapeToken();
        repoName = files.get(0).getRepositoryName();
      }
      tx.commit();
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      final String context =
          files.isEmpty()
              ? "empty batch"
              : files.size()
                  + " files for repo '"
                  + files.get(0).getRepositoryName()
                  + "', landscape '"
                  + files.get(0).getLandscapeToken()
                  + "'";
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, context));
    }

    if (landscapeToken != null) {
      updatePendingCommitMetrics(session, landscapeToken, repoName);
    }

    return Uni.createFrom().item(Empty.getDefaultInstance());
  }

  private void updatePendingCommitMetrics(
      final Session session, final String landscapeToken, final String repoName) {
    try (Transaction tx = session.beginTransaction()) {
      commitMetricsAccumulator.updatePendingForRepository(session, landscapeToken, repoName);
      tx.commit();
    } finally {
      session.clear();
    }
  }
}
