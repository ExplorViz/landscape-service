package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.Field;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.proto.ClassData;
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
    return persistSingleFile(request);
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

  private Uni<Empty> persistSingleFile(final FileData request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      saveFileData(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    }
  }

  private void saveFileData(final Session session, final FileData fileData) {
    final FileRevision file =
        session.queryForObject(
            FileRevision.class,
            """
            MATCH (f:FileRevision {repoName: $repoName, filePath: $filePath, hash: $hash})
            RETURN f LIMIT 1
            """,
            Map.of(
                "repoName", fileData.getRepositoryName(),
                "filePath", fileData.getFilePath(),
                "hash", fileData.getFileHash()));

    if (file == null) {
      throw Status.FAILED_PRECONDITION
          .withDescription("No corresponding file was sent before in CommitData.")
          .asRuntimeException();
    }

    file.setLanguage(fileData.getLanguage().toString());
    file.setPackageName(fileData.getPackageName());
    file.setImportNames(fileData.getImportNamesList());
    file.setMetrics(fileData.getMetricsMap());
    file.setLastEditor(fileData.getLastEditor());
    file.setAddedLines(fileData.getAddedLines());
    file.setModifiedLines(fileData.getModifiedLines());
    file.setDeletedLines(fileData.getDeletedLines());

    fileData.getClassesList().forEach(c -> file.addClass(buildClazz(c)));

    fileData.getFunctionsList().forEach(f -> file.addFunction(new Function(f)));

    file.setHasFileData(true);

    // One OGM save traverses the full object graph; OGM batches same-label nodes into UNWIND
    // queries, replacing the previous per-class session.save(clazz) round-trips.
    session.save(file);
  }

  /**
   * Builds a {@link Clazz} node and its full sub-graph (fields, functions, inner classes) entirely
   * in memory. Superclass references are stored as FQN strings on the node, so no DB round-trips
   * are needed. The graph is flushed by the single {@code session.save(file)} call in {@link
   * #saveFileData}.
   */
  private Clazz buildClazz(final ClassData classData) {
    final Clazz clazz = new Clazz(classData.getName());
    clazz.setType(classData.getType());
    clazz.setModifiers(classData.getModifiersList());
    clazz.setImplementedInterfaces(classData.getImplementedInterfacesList());
    clazz.setAnnotations(classData.getAnnotationsList());
    clazz.setEnumValues(classData.getEnumValuesList());
    clazz.setMetrics(classData.getMetricsMap());
    clazz.setSuperclassFqns(classData.getSuperclassesList());

    classData
        .getFieldsList()
        .forEach(f -> clazz.addField(new Field(f.getName(), f.getType(), f.getModifiersList())));

    classData.getFunctionsList().forEach(f -> clazz.addFunction(new Function(f)));

    classData.getInnerClassesList().forEach(c -> clazz.addInnerClass(buildClazz(c)));

    return clazz;
  }
}
