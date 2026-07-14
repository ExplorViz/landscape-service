package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.FileIdentifier;
import org.neo4j.ogm.session.Session;

/** Links file revisions to commits during {@code CommitData} persistence. */
@ApplicationScoped
public class CommitFileLinker {

  @Inject PendingCommitContextRegistry pendingCommitContextRegistry;
  @Inject CommitMetricsAccumulator commitMetricsAccumulator;
  @Inject FileRevisionRepository fileRevisionRepository;
  @Inject CommitStaleFileRevisionUnlinker commitStaleFileRevisionUnlinker;

  public CommitFileLinkTimings linkCommitFiles(
      final Session session,
      final CommitData commitData,
      final Commit commit,
      final CommitFilePersistenceContext fileContext) {
    final boolean metadataOnlyCommit = CommitFileStubPolicy.isMetadataOnlyCommit(commitData);
    final boolean deferFileStubs = CommitFileStubPolicy.defersFileStubCreation(commitData);
    final Set<String> addedPaths = toFilePaths(commitData.getAddedFilesList());
    final Set<String> modifiedPaths = toFilePaths(commitData.getModifiedFilesList());
    final Set<String> deletedPaths = toFilePaths(commitData.getDeletedFilesList());

    if (metadataOnlyCommit) {
      Log.debugf(
          "Commit %s for repository '%s': metadata-only commit; skipping file linking and metric"
              + " accumulation",
          commitData.getCommitId(), commitData.getRepositoryName());
      return CommitFileLinkTimings.metadataOnly(deletedPaths);
    }

    if (deferFileStubs) {
      registerDeferredFileStubs(commitData, commit);
      return CommitFileLinkTimings.deferred(deletedPaths);
    }

    return linkAnalyzedCommitFiles(
        session, commitData, commit, fileContext, addedPaths, modifiedPaths, deletedPaths);
  }

  private void registerDeferredFileStubs(final CommitData commitData, final Commit commit) {
    pendingCommitContextRegistry.register(
        new PendingCommitContextRegistry.PendingCommit(
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            commitData.getCommitId(),
            commit.getId(),
            commitData.getAnalysisFileCount()));
    Log.warnf(
        "Commit %s for repository '%s': received metadata-only CommitData from an outdated "
            + "analyzer (%d files expected, %d deleted paths). "
            + "File stubs will not be pre-linked; "
            + "upgrade the code-analyzer to send file identifiers in CommitData.",
        commitData.getCommitId(),
        commitData.getRepositoryName(),
        commitData.getAnalysisFileCount(),
        commitData.getDeletedFilesList().size());
  }

  private CommitFileLinkTimings linkAnalyzedCommitFiles(
      final Session session,
      final CommitData commitData,
      final Commit commit,
      final CommitFilePersistenceContext fileContext,
      final Set<String> addedPaths,
      final Set<String> modifiedPaths,
      final Set<String> deletedPaths) {
    pendingCommitContextRegistry.unregister(
        commitData.getLandscapeToken(), commitData.getRepositoryName());
    commitMetricsAccumulator.updatePendingForRepository(
        session, commitData.getLandscapeToken(), commitData.getRepositoryName());

    long stepStart = System.nanoTime();
    fileRevisionRepository.persistCommitFilesInBatches(
        session,
        fileContext,
        commitData.getAddedFilesList(),
        FileRevisionRepository.CommitFileLinkType.ADDED);
    final long linkAddedFilesMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    fileRevisionRepository.persistCommitFilesInBatches(
        session,
        fileContext,
        commitData.getModifiedFilesList(),
        FileRevisionRepository.CommitFileLinkType.MODIFIED);
    final long linkModifiedFilesMs = elapsedMillis(stepStart);

    long linkUnchangedFilesMs = 0;
    if (!commitData.getUnchangedFilesList().isEmpty()) {
      stepStart = System.nanoTime();
      fileRevisionRepository.persistCommitFilesInBatches(
          session,
          fileContext,
          commitData.getUnchangedFilesList(),
          FileRevisionRepository.CommitFileLinkType.CONTAINS);
      linkUnchangedFilesMs = elapsedMillis(stepStart);
    }

    long copyUnchangedFromParentMs = 0;
    long unlinkStaleRevisionsMs = 0;
    if (ParentCommitInheritancePolicy.hasPersistedParentReference(commitData)) {
      stepStart = System.nanoTime();
      final int copiedFromParent =
          fileRevisionRepository.copyUnchangedFilesFromParentCommit(
              session,
              new FileRevisionRepository.CopyUnchangedFilesFromParentRequest(
                  commitData.getLandscapeToken(),
                  commitData.getRepositoryName(),
                  commitData.getParentCommitId(),
                  commit.getId(),
                  addedPaths,
                  modifiedPaths,
                  deletedPaths,
                  ParentCommitInheritancePolicy.requiresPersistedParent(commitData)));
      copyUnchangedFromParentMs = elapsedMillis(stepStart);

      if (copiedFromParent > 0 && (!addedPaths.isEmpty() || !modifiedPaths.isEmpty())) {
        stepStart = System.nanoTime();
        commitStaleFileRevisionUnlinker.unlinkStaleRevisionsAtChangedPaths(
            session, commit.getId(), commitData.getRepositoryName(), modifiedPaths, addedPaths);
        unlinkStaleRevisionsMs = elapsedMillis(stepStart);
      }
    }

    return CommitFileLinkTimings.analyzed(
        deletedPaths,
        linkAddedFilesMs,
        linkModifiedFilesMs,
        linkUnchangedFilesMs,
        copyUnchangedFromParentMs,
        unlinkStaleRevisionsMs);
  }

  private static Set<String> toFilePaths(final List<FileIdentifier> files) {
    return files.stream()
        .map(FileIdentifier::getFilePath)
        .collect(Collectors.toCollection(HashSet::new));
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
