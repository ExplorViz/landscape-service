package net.explorviz.landscape.messaging.service;

import com.google.common.collect.ObjectArrays;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import net.explorviz.landscape.avro.CodeDescriptor;
import net.explorviz.landscape.avro.SpanData;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.ogm.Landscape;
import net.explorviz.landscape.ogm.Span;
import net.explorviz.landscape.ogm.Trace;
import net.explorviz.landscape.repository.ApplicationRepository;
import net.explorviz.landscape.repository.ClazzRepository;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.FunctionRepository;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.SpanRepository;
import net.explorviz.landscape.repository.TraceRepository;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class SpanPersistenceService {

  @Inject ApplicationRepository applicationRepository;

  @Inject ClazzRepository clazzRepository;

  @Inject CommitRepository commitRepository;

  @Inject FileRevisionRepository fileRevisionRepository;

  @Inject FunctionRepository functionRepository;

  @Inject LandscapeRepository landscapeRepository;

  @Inject SpanRepository spanRepository;

  @Inject SessionFactory sessionFactory;

  @Inject TraceRepository traceRepository;

  public void saveSpanData(final Session session, final SpanData spanData) {
    final Span span = spanRepository.getOrCreateSpan(session, spanData.getSpanId());

    span.setStartTime(spanData.getStartTime());
    span.setEndTime(spanData.getEndTime());

    if (spanData.getParentId() != null && !spanData.getParentId().isEmpty()) {
      final Span parentSpan = spanRepository.getOrCreateSpan(session, spanData.getParentId());
      span.setParentSpan(parentSpan);
    }

    final Trace trace = traceRepository.getOrCreateTrace(session, spanData.getTraceId());
    trace.addSpan(span);

    final Landscape landscape =
        landscapeRepository.getOrCreateLandscape(session, spanData.getLandscapeTokenId());
    landscape.addTrace(trace);

    final Object entityDescriptor = spanData.getEntityDescriptor();

    if (entityDescriptor instanceof CodeDescriptor codeDescriptor) {
      final Function function;
      if (codeDescriptor.getCommitHash() != null) {
        function =
            resolveFunctionFqn(
                session, spanData, codeDescriptor, codeDescriptor.getCommitHash(), landscape);
      } else {
        function = resolveFunctionFqn(session, spanData, codeDescriptor, landscape);
      }

      span.setFunction(function);
      session.save(List.of(span, trace, landscape, function));
    } else {
      throw new IllegalStateException(
          "Unknown entity descriptor type: " + entityDescriptor.getClass());
    }
  }

  private Function resolveFunctionFqn(
      final Session session,
      final SpanData spanData,
      final CodeDescriptor codeDescriptor,
      final Landscape landscape) {
    final String[] splitFilePath = codeDescriptor.getFilePath().split("/");
    final String functionName = codeDescriptor.getFunctionName();

    final FileRevision fileRevision =
        resolveFileRevision(session, spanData, splitFilePath, landscape);
    if (codeDescriptor.getLanguage() != null) {
      fileRevision.setLanguage(codeDescriptor.getLanguage().toUpperCase(Locale.ENGLISH));
    }
    session.save(fileRevision);

    final Function function;

    if (codeDescriptor.getClassName() != null) {
      final Clazz clazz =
          clazzRepository
              .findClassByClassPathAndFileRevisionId(
                  session, codeDescriptor.getClassName().split("\\."), fileRevision.getId())
              .orElseGet(
                  () ->
                      clazzRepository.createClazzPathAndReturnLastClazz(
                          session,
                          codeDescriptor.getClassName().split("\\."),
                          fileRevision.getId()));

      function =
          functionRepository
              .findFunctionByFunctionNameAndClazzId(session, functionName, clazz.getId())
              .orElse(new Function(functionName));
      clazz.addFunction(function);
      session.save(clazz);
    } else {
      function =
          functionRepository
              .findFunctionWithFunctionNameAndFileRevisionId(
                  session, functionName, fileRevision.getId())
              .orElse(new Function(functionName));
      fileRevision.addFunction(function);
      session.save(fileRevision);
    }

    return function;
  }

  private Function resolveFunctionFqn(
      final Session session,
      final SpanData spanData,
      final CodeDescriptor codeDescriptor,
      final String commitHash,
      final Landscape landscape) {
    final String[] splitFilePath = codeDescriptor.getFilePath().split("/");
    final String functionName = codeDescriptor.getFunctionName();

    if (codeDescriptor.getClassName() != null) {
      return functionRepository
          .findFunction(
              session,
              spanData.getApplicationName(),
              ObjectArrays.concat(splitFilePath, functionName),
              spanData.getLandscapeTokenId(),
              commitHash,
              codeDescriptor.getClassName().split("\\."))
          .orElseGet(() -> resolveFunctionFqn(session, spanData, codeDescriptor, landscape));
    } else {
      return functionRepository
          .findFunction(
              session,
              spanData.getApplicationName(),
              ObjectArrays.concat(splitFilePath, functionName),
              commitHash,
              spanData.getLandscapeTokenId())
          .orElseGet(() -> resolveFunctionFqn(session, spanData, codeDescriptor, landscape));
    }
  }

  private FileRevision resolveFileRevision(
      final Session session,
      final SpanData spanData,
      final String[] splitFileFqn,
      final Landscape landscape) {
    return fileRevisionRepository
        .findFileRevisionFromAppNameAndPathWithoutCommit(
            session, spanData.getApplicationName(), splitFileFqn, spanData.getLandscapeTokenId())
        .orElseGet(
            () -> {
              final Application application =
                  applicationRepository
                      .findApplicationByNameAndLandscapeToken(
                          session, spanData.getApplicationName(), spanData.getLandscapeTokenId())
                      .orElse(new Application(spanData.getApplicationName()));
              landscape.addApplication(application);

              final FileRevision fileRevision;

              if (application.getRootDirectory() == null) {
                // Application did not previously exist, build file structure from scratch
                fileRevision =
                    fileRevisionRepository.createFileStructureForNewApplicationFromFqn(
                        session, application, splitFileFqn);
              } else {
                // Create missing directories and file for existing Application
                fileRevision =
                    fileRevisionRepository.createFileStructureForExistingApplicationFromFileFqn(
                        session,
                        splitFileFqn,
                        application.getName(),
                        spanData.getLandscapeTokenId());
              }

              return fileRevision;
            });
  }
}
