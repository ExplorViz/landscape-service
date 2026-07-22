package net.explorviz.landscape.messaging.service;

import com.google.common.collect.ObjectArrays;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.ogm.Landscape;
import net.explorviz.landscape.ogm.Span;
import net.explorviz.landscape.ogm.Trace;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.ParsedSpan;
import net.explorviz.landscape.repository.ApplicationRepository;
import net.explorviz.landscape.repository.ClazzRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.FunctionRepository;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.SpanRepository;
import net.explorviz.landscape.repository.TraceRepository;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class SpanPersistenceService {

  @Inject ApplicationRepository applicationRepository;

  @Inject ClazzRepository clazzRepository;

  @Inject FileRevisionRepository fileRevisionRepository;

  @Inject FunctionRepository functionRepository;

  @Inject LandscapeRepository landscapeRepository;

  @Inject SpanRepository spanRepository;

  @Inject TraceRepository traceRepository;

  public void saveSpan(final Session session, final ParsedSpan parsedSpan) {
    final Span span = spanRepository.getOrCreateSpan(session, parsedSpan.getSpanId());

    span.setStartTime(parsedSpan.getStartTime());
    span.setEndTime(parsedSpan.getEndTime());

    if (parsedSpan.hasParentId() && !parsedSpan.getParentId().isEmpty()) {
      final Span parentSpan = spanRepository.getOrCreateSpan(session, parsedSpan.getParentId());
      span.setParentSpan(parentSpan);
    }

    final Trace trace = traceRepository.getOrCreateTrace(session, parsedSpan.getTraceId());
    trace.addSpan(span);

    final Landscape landscape =
        landscapeRepository.getOrCreateLandscape(session, parsedSpan.getLandscapeTokenId());
    landscape.addTrace(trace);

    switch (parsedSpan.getEntityDescriptorCase()) { // NOPMD
      case CODE_DESCRIPTOR -> {
        if (!parsedSpan.hasCodeDescriptor()) {
          throw new IllegalStateException(
              "Entity descriptor case set to code descriptor, but none present");
        }
        final CodeDescriptor codeDescriptor = parsedSpan.getCodeDescriptor();
        final Function function;
        if (codeDescriptor.hasGitCommitHash() && !codeDescriptor.getGitCommitHash().isEmpty()) {
          function =
              resolveFunctionFqn(
                  session,
                  parsedSpan,
                  codeDescriptor,
                  codeDescriptor.getGitCommitHash(),
                  landscape);
        } else {
          function = resolveFunctionFqn(session, parsedSpan, codeDescriptor, landscape);
        }
        span.setFunction(function);
        session.save(List.of(span, trace, landscape));
      }

      default ->
          throw new IllegalStateException(
              "Unhandled entity descriptor type: " + parsedSpan.getEntityDescriptorCase());
    }
  }

  private Function resolveFunctionFqn(
      final Session session,
      final ParsedSpan parsedSpan,
      final CodeDescriptor codeDescriptor,
      final Landscape landscape) {
    final String[] splitFilePath = codeDescriptor.getFilePath().split("/");
    final String functionName = codeDescriptor.getFunctionName();

    final FileRevision fileRevision =
        resolveFileRevision(session, parsedSpan, splitFilePath, landscape);
    fileRevision.setLanguage(codeDescriptor.getLanguage().toUpperCase(Locale.ENGLISH));
    session.save(fileRevision);

    final Function function;

    if (codeDescriptor.hasClassName()) {
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
      final ParsedSpan parsedSpan,
      final CodeDescriptor codeDescriptor,
      final String commitHash,
      final Landscape landscape) {
    final String[] splitFilePath = codeDescriptor.getFilePath().split("/");
    final String functionName = codeDescriptor.getFunctionName();

    if (codeDescriptor.hasClassName()) {
      return functionRepository
          .findFunction(
              session,
              parsedSpan.getApplicationName(),
              ObjectArrays.concat(splitFilePath, functionName),
              parsedSpan.getLandscapeTokenId(),
              commitHash,
              codeDescriptor.getClassName().split("\\."))
          .orElseGet(() -> resolveFunctionFqn(session, parsedSpan, codeDescriptor, landscape));
    } else {
      return functionRepository
          .findFunction(
              session,
              parsedSpan.getApplicationName(),
              ObjectArrays.concat(splitFilePath, functionName),
              commitHash,
              parsedSpan.getLandscapeTokenId())
          .orElseGet(() -> resolveFunctionFqn(session, parsedSpan, codeDescriptor, landscape));
    }
  }

  private FileRevision resolveFileRevision(
      final Session session,
      final ParsedSpan parsedSpan,
      final String[] splitFileFqn,
      final Landscape landscape) {
    return fileRevisionRepository
        .findFileRevisionFromAppNameAndPathWithoutCommit(
            session,
            parsedSpan.getApplicationName(),
            splitFileFqn,
            parsedSpan.getLandscapeTokenId())
        .orElseGet(
            () -> {
              final Application application =
                  applicationRepository
                      .findApplicationByNameAndLandscapeToken(
                          session,
                          parsedSpan.getApplicationName(),
                          parsedSpan.getLandscapeTokenId())
                      .orElse(new Application(parsedSpan.getApplicationName()));
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
                        parsedSpan.getLandscapeTokenId());
              }

              return fileRevision;
            });
  }
}
