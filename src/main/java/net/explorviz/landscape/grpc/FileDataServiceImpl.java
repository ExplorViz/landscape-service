package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.Map;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.Field;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.proto.ClassData;
import net.explorviz.landscape.proto.FileData;
import net.explorviz.landscape.proto.FileDataService;
import net.explorviz.landscape.repository.ClazzRepository;
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class FileDataServiceImpl implements FileDataService {

  @Inject ClazzRepository clazzRepository;
  @Inject SessionFactory sessionFactory;

  @Blocking
  @Override
  public Uni<Empty> persistFile(final FileData request) {
    return persistSingleFile(request);
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

    fileData.getClassesList().forEach(c -> file.addClass(buildClazz(session, c, fileData)));

    fileData.getFunctionsList().forEach(f -> file.addFunction(new Function(f)));

    file.setHasFileData(true);

    // One OGM save traverses the full object graph; OGM batches same-label nodes into UNWIND
    // queries, replacing the previous per-class session.save(clazz) round-trips.
    session.save(file);
  }

  /**
   * Builds a {@link Clazz} node and its full sub-graph (fields, functions, inner classes,
   * superclass links) entirely in memory. No intermediate database writes are performed; the graph
   * is flushed by the single {@code session.save(file)} call in {@link #saveFileData}.
   *
   * <p>Superclass nodes are looked up via an indexed property access so that cross-file inheritance
   * links remain correct even when files are processed out of declaration order.
   */
  private Clazz buildClazz(
      final Session session, final ClassData classData, final FileData fileData) {
    final Clazz clazz = resolveOrCreateClazz(session, classData, fileData);
    clazz.setType(classData.getType());
    clazz.setModifiers(classData.getModifiersList());
    clazz.setImplementedInterfaces(classData.getImplementedInterfacesList());
    clazz.setAnnotations(classData.getAnnotationsList());
    clazz.setEnumValues(classData.getEnumValuesList());
    clazz.setMetrics(classData.getMetricsMap());

    classData
        .getFieldsList()
        .forEach(f -> clazz.addField(new Field(f.getName(), f.getType(), f.getModifiersList())));

    classData.getFunctionsList().forEach(f -> clazz.addFunction(new Function(f)));

    classData
        .getInnerClassesList()
        .forEach(c -> clazz.addInnerClass(buildClazz(session, c, fileData)));

    // Superclass nodes may already exist (from previously processed files).
    classData
        .getSuperclassesList()
        .forEach(
            superFqn -> {
              final String[] splitSuperFqn = superFqn.split("::");
              clazz.addSuperclass(
                  clazzRepository
                      .findClassByLandscapeTokenAndRepositoryAndClazzFqn(
                          session,
                          fileData.getLandscapeToken(),
                          fileData.getRepositoryName(),
                          splitSuperFqn)
                      .orElse(new Clazz(splitSuperFqn[1])));
            });

    return clazz;
  }

  /**
   * Reuses a placeholder {@link Clazz} node when another file referenced this class via INHERITS
   * before its defining file was analyzed. If the matched class is already fully populated from
   * another commit, a fresh node is created instead.
   */
  private Clazz resolveOrCreateClazz(
      final Session session, final ClassData classData, final FileData fileData) {
    return clazzRepository
        .findClassFromInheritingClass(
            session,
            fileData.getLandscapeToken(),
            fileData.getRepositoryName(),
            classData.getName())
        .map(
            existingClazz ->
                existingClazz.getType() == null ? existingClazz : new Clazz(classData.getName()))
        .orElseGet(() -> new Clazz(classData.getName()));
  }
}
