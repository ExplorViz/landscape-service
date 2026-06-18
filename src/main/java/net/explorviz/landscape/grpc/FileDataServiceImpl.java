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
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class FileDataServiceImpl implements FileDataService {

  @Inject SessionFactory sessionFactory;
  @Inject FileDataBatchWriter fileDataBatchWriter;

  @Blocking
  @Override
  public Uni<Empty> persistFile(final FileData request) {
    return persistBatch(List.of(request));
  }

  /**
   * Client-streaming variant: accepts a stream of {@link FileData} messages for an entire commit,
   * collects them, and persists the whole set in a single Neo4j transaction using batched UNWIND
   * Cypher queries. This reduces bolt round-trips from O(12 × N) to O(max_class_depth + 6)
   * regardless of N.
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
    try (Transaction tx = session.beginTransaction()) {
      fileDataBatchWriter.persistBatch(session, files);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
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
  }
}
