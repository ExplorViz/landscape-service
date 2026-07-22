package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import net.explorviz.landscape.proto.CommitData;

/** Logs timing breakdowns for commit persistence. */
@ApplicationScoped
public class PersistCommitTimingLogger {

  public void log(
      final CommitData commitData,
      final CommitFileLinkTimings fileLinkTimings,
      final PersistCommitTimingReport timingReport) {
    Log.infof(
        "persistCommit(commit=%s, repo='%s', metadataOnly=%s, deferFileStubs=%s):"
            + " resolveRepository=%dms,"
            + " setupCommit=%dms, createFileContext=%dms, linkAddedFiles(%d)=%dms,"
            + " linkModifiedFiles(%d)=%dms, linkUnchangedFiles(%d)=%dms,"
            + " copyUnchangedFromParent=%dms, unlinkStaleRevisions(%d)=%dms,"
            + " unlinkDeletedFiles(%d)=%dms, verifyCache=%dms, tags(%d)=%dms,"
            + " linkParentCommits=%dms, finalizeCommit=%dms, total=%dms",
        commitData.getCommitId(),
        commitData.getRepositoryName(),
        fileLinkTimings.metadataOnlyCommit(),
        fileLinkTimings.deferFileStubs(),
        timingReport.resolveRepositoryMs(),
        timingReport.setupCommitMs(),
        timingReport.createFileContextMs(),
        commitData.getAddedFilesList().size(),
        fileLinkTimings.linkAddedFilesMs(),
        commitData.getModifiedFilesList().size(),
        fileLinkTimings.linkModifiedFilesMs(),
        commitData.getUnchangedFilesList().size(),
        fileLinkTimings.linkUnchangedFilesMs(),
        fileLinkTimings.copyUnchangedFromParentMs(),
        commitData.getAddedFilesList().size() + commitData.getModifiedFilesList().size(),
        fileLinkTimings.unlinkStaleRevisionsMs(),
        fileLinkTimings.deletedPaths().size(),
        timingReport.unlinkDeletedFilesMs(),
        timingReport.verifyCacheMs(),
        commitData.getTagsList().size(),
        timingReport.tagsMs(),
        timingReport.linkParentCommitsMs(),
        timingReport.finalizeCommitMs(),
        timingReport.totalMs());
  }
}
