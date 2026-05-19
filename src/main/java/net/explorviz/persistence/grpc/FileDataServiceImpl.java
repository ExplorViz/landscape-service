package net.explorviz.persistence.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import net.explorviz.persistence.ogm.Clazz;
import net.explorviz.persistence.ogm.Field;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.ogm.Function;
import net.explorviz.persistence.proto.ClassData;
import net.explorviz.persistence.proto.FileData;
import net.explorviz.persistence.proto.FileDataService;
import net.explorviz.persistence.repository.ClazzRepository;
import net.explorviz.persistence.repository.FileRevisionRepository;
import net.explorviz.persistence.repository.FunctionRepository;
import net.explorviz.persistence.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class FileDataServiceImpl implements FileDataService {

  @Inject ClazzRepository clazzRepository;

  @Inject FileRevisionRepository fileRevisionRepository;

  @Inject FunctionRepository functionRepository;

  @Inject SessionFactory sessionFactory;

  @Blocking
  @Override
  public Uni<Empty> persistFile(final FileData request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      saveFileData(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GGrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    }
  }

  private void saveFileData(final Session session, final FileData fileData) {
    final FileRevision file =
        fileRevisionRepository
            .getFileRevisionFromHashAndPath(
                session,
                fileData.getFileHash(),
                fileData.getRepositoryName(),
                fileData.getLandscapeToken(),
                fileData.getFilePath().split("/"))
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("No corresponding file was sent before in CommitData.")
                        .asRuntimeException());

    file.setLanguage(fileData.getLanguage().toString());
    file.setPackageName(fileData.getPackageName());
    file.setImportNames(fileData.getImportNamesList());
    file.setMetrics(fileData.getMetricsMap());
    file.setLastEditor(fileData.getLastEditor());
    file.setAddedLines(fileData.getAddedLines());
    file.setModifiedLines(fileData.getModifiedLines());
    file.setDeletedLines(fileData.getDeletedLines());

    fileData.getClassesList().forEach(c -> file.addClass(createClazz(session, c, fileData)));

    fileData.getFunctionsList().forEach(f -> file.addFunction(new Function(f)));

    file.setHasFileData(true);

    session.save(file);
  }

  private Clazz createClazz(
      final Session session, final ClassData classData, final FileData fileData) {
    return clazzRepository
        .findClassByLandscapeTokenAndRepositoryAndFileHashAndClazzName(
            session,
            fileData.getLandscapeToken(),
            fileData.getRepositoryName(),
            fileData.getFileHash(),
            classData.getName())
        .orElseGet(
            () -> {
              final Clazz clazz =
                  clazzRepository
                      .findClassFromInheritingClass(
                          session,
                          fileData.getLandscapeToken(),
                          fileData.getRepositoryName(),
                          classData.getName())
                      .map(
                          foundClazz -> {
                            if (foundClazz.getType() == null) {
                              foundClazz.setType(classData.getType());
                              foundClazz.setModifiers(classData.getModifiersList());
                              foundClazz.setImplementedInterfaces(
                                  classData.getImplementedInterfacesList());
                              foundClazz.setAnnotations(classData.getAnnotationsList());
                              foundClazz.setEnumValues(classData.getEnumValuesList());
                              foundClazz.setMetrics(classData.getMetricsMap());
                            }
                            return foundClazz;
                          })
                      /*
                       If found clazz has a type, then it must be from another commit,
                       therefore we create a new Clazz object. Same for clazz is null.
                      */
                      .orElseGet(
                          () -> {
                            final Clazz newClazz = new Clazz(classData.getName());
                            newClazz.setType(classData.getType());
                            newClazz.setModifiers(classData.getModifiersList());
                            newClazz.setImplementedInterfaces(
                                classData.getImplementedInterfacesList());
                            newClazz.setAnnotations(classData.getAnnotationsList());
                            newClazz.setEnumValues(classData.getEnumValuesList());
                            newClazz.setMetrics(classData.getMetricsMap());
                            return newClazz;
                          });

              classData
                  .getFieldsList()
                  .forEach(
                      f ->
                          clazz.addField(
                              new Field(f.getName(), f.getType(), f.getModifiersList())));

              classData
                  .getInnerClassesList()
                  .forEach(c -> clazz.addInnerClass(createClazz(session, c, fileData)));

              classData.getFunctionsList().forEach(f -> clazz.addFunction(new Function(f)));

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

              session.save(clazz);

              return clazz;
            });
  }
}
