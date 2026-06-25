package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.ogm.Tag;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.CommitService;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.repository.ApplicationRepository;
import net.explorviz.landscape.repository.BranchRepository;
import net.explorviz.landscape.repository.CommitDeletedFileUnlinker;
import net.explorviz.landscape.repository.CommitFilePersistenceContext;
import net.explorviz.landscape.repository.CommitFileStubPolicy;
import net.explorviz.landscape.repository.CommitMetricsAccumulator;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.ContributorRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.PendingCommitContextRegistry;
import net.explorviz.landscape.repository.RepositoryRepository;
import net.explorviz.landscape.repository.TagRepository;
import net.explorviz.landscape.repository.UnchangedCommitFileCopier;
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class CommitServiceImpl implements CommitService {

  private static final String NO_PARENT_ID = "NONE";

  /**
   * Parent commits with at most this many extra links beyond ADDED/MODIFIED are treated as
   * incomplete.
   */
  private static final int INCOMPLETE_PARENT_TOLERANCE = 5;

  @Inject ApplicationRepository applicationRepository;
  @Inject BranchRepository branchRepository;
  @Inject CommitRepository commitRepository;
  @Inject LandscapeRepository landscapeRepository;
  @Inject RepositoryRepository repositoryRepository;
  @Inject FileRevisionRepository fileRevisionRepository;
  @Inject UnchangedCommitFileCopier unchangedCommitFileCopier;
  @Inject CommitDeletedFileUnlinker commitDeletedFileUnlinker;
  @Inject CommitMetricsAccumulator commitMetricsAccumulator;
  @Inject PendingCommitContextRegistry pendingCommitContextRegistry;
  @Inject TagRepository tagRepository;
  @Inject ContributorRepository contributorRepository;
  @Inject SessionFactory sessionFactory;

  @Blocking
  @Override
  public Uni<Empty> persistCommit(final CommitData request) {
    final Session session = sessionFactory.openSession();
    try (Transaction tx = session.beginTransaction()) {
      saveCommitData(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    } finally {
      session.clear();
    }
  }

  @SuppressWarnings({"PMD.NcssCount", "PMD.CyclomaticComplexity", "PMD.NPathComplexity"})
  public void saveCommitData(final Session session, final CommitData commitData) {
    final long commitStart = System.nanoTime();
    long stepStart = commitStart;

    final Repository repo =
        repositoryRepository
            .findRepositoryByNameAndLandscapeToken(
                session, commitData.getRepositoryName(), commitData.getLandscapeToken())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("No corresponding state data was sent before.")
                        .asRuntimeException());
    final long resolveRepositoryMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final Branch branch =
        branchRepository.getOrCreateBranch(
            session,
            commitData.getBranchName(),
            commitData.getRepositoryName(),
            commitData.getLandscapeToken());
    repo.addBranch(branch);

    final Commit commit =
        commitRepository.getOrCreateCommit(
            session, commitData.getCommitId(), commitData.getLandscapeToken());
    if (commitData.hasAuthor()) {
      final Contributor author =
          contributorRepository.getOrCreateContributor(session, commitData.getAuthor());
      commit.setAuthor(author);
      author.addCommit(commit);
    }
    commit.setBranch(branch);
    commit.setCommitDate(
        Instant.ofEpochSecond(
            commitData.getCommitDate().getSeconds(), commitData.getCommitDate().getNanos()));
    commit.setAuthorDate(
        Instant.ofEpochSecond(
            commitData.getAuthorDate().getSeconds(), commitData.getAuthorDate().getNanos()));
    repo.addCommit(commit);
    session.save(commit);
    final long setupCommitMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final CommitFilePersistenceContext fileContext =
        fileRevisionRepository.createFilePersistenceContext(
            session,
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            commit.getId());
    final long createFileContextMs = elapsedMillis(stepStart);

    final boolean deferFileStubs = CommitFileStubPolicy.defersFileStubCreation(commitData);
    long linkAddedFilesMs = 0;
    long linkModifiedFilesMs = 0;
    long linkUnchangedFilesMs = 0;

    stepStart = System.nanoTime();
    if (deferFileStubs) {
      pendingCommitContextRegistry.register(
          new PendingCommitContextRegistry.PendingCommit(
              commitData.getLandscapeToken(),
              commitData.getRepositoryName(),
              commitData.getCommitId(),
              commit.getId(),
              commitData.getAnalysisFileCount()));
      Log.infof(
          "Commit %s for repository '%s': deferring file stub creation to FileData pipeline "
              + "(%d files to analyze, %d deleted paths)",
          commitData.getCommitId(),
          commitData.getRepositoryName(),
          commitData.getAnalysisFileCount(),
          commitData.getDeletedFilesList().size());
    } else {
      pendingCommitContextRegistry.unregister(
          commitData.getLandscapeToken(), commitData.getRepositoryName());

      commitMetricsAccumulator.updatePendingForRepository(
          session, commitData.getLandscapeToken(), commitData.getRepositoryName());

      fileRevisionRepository.persistCommitFilesInBatches(
          session,
          fileContext,
          commitData.getAddedFilesList(),
          FileRevisionRepository.CommitFileLinkType.ADDED);
      linkAddedFilesMs = elapsedMillis(stepStart);

      stepStart = System.nanoTime();
      fileRevisionRepository.persistCommitFilesInBatches(
          session,
          fileContext,
          commitData.getModifiedFilesList(),
          FileRevisionRepository.CommitFileLinkType.MODIFIED);
      linkModifiedFilesMs = elapsedMillis(stepStart);

      stepStart = System.nanoTime();
      fileRevisionRepository.persistCommitFilesInBatches(
          session,
          fileContext,
          commitData.getUnchangedFilesList(),
          FileRevisionRepository.CommitFileLinkType.CONTAINS);
      linkUnchangedFilesMs = elapsedMillis(stepStart);
    }

    final Set<String> deletedPaths =
        commitData.getDeletedFilesList().stream()
            .map(FileIdentifier::getFilePath)
            .collect(Collectors.toCollection(HashSet::new));

    stepStart = System.nanoTime();
    final boolean hasParent =
        !commitData.getParentCommitId().isEmpty()
            && !NO_PARENT_ID.equals(commitData.getParentCommitId());
    if (!deferFileStubs && hasParent && commitData.getUnchangedFilesList().isEmpty()) {
      final Set<String> addedPaths =
          commitData.getAddedFilesList().stream()
              .map(FileIdentifier::getFilePath)
              .collect(Collectors.toCollection(HashSet::new));
      final Set<String> modifiedPaths =
          commitData.getModifiedFilesList().stream()
              .map(FileIdentifier::getFilePath)
              .collect(Collectors.toCollection(HashSet::new));

      copyUnchangedFromParent(session, commitData, commit, addedPaths, modifiedPaths, deletedPaths);
    }
    final long copyUnchangedFromParentMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    if (!deletedPaths.isEmpty()) {
      commitDeletedFileUnlinker.unlinkDeletedFilesFromCommit(session, commit.getId(), deletedPaths);
    }
    final long unlinkDeletedFilesMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    commitData
        .getTagsList()
        .forEach(
            tagName -> {
              final Tag tag =
                  tagRepository
                      .findTagByNameAndRepositoryNameAndLandscapeToken(
                          session, tagName, repo.getName(), commitData.getLandscapeToken())
                      .orElse(new Tag(tagName));
              commit.addTag(tag);
              repo.addTag(tag);
            });
    final long tagsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    if (commitData.getParentCommitId().isEmpty()
        || NO_PARENT_ID.equals(commitData.getParentCommitId())) {
      session.save(List.of(repo, branch, commit));
    } else {
      final Commit parentCommit =
          commitRepository.getOrCreateCommit(
              session, commitData.getParentCommitId(), commitData.getLandscapeToken());
      commit.addParentCommit(parentCommit);
      session.save(List.of(repo, branch, commit, parentCommit));
    }
    final long finalizeCommitMs = elapsedMillis(stepStart);

    if (!deferFileStubs) {
      commitMetricsAccumulator.updatePendingForCommit(
          session,
          commitData.getLandscapeToken(),
          commitData.getRepositoryName(),
          commitData.getCommitId());
    }

    Log.infof(
        "persistCommit(commit=%s, repo='%s', deferFileStubs=%s): resolveRepository=%dms, "
            + "setupCommit=%dms, createFileContext=%dms, linkAddedFiles(%d)=%dms, "
            + "linkModifiedFiles(%d)=%dms, linkUnchangedFiles(%d)=%dms, "
            + "copyUnchangedFromParent=%dms, unlinkDeletedFiles(%d)=%dms, "
            + "tags(%d)=%dms, finalizeCommit=%dms, total=%dms",
        commitData.getCommitId(),
        commitData.getRepositoryName(),
        deferFileStubs,
        resolveRepositoryMs,
        setupCommitMs,
        createFileContextMs,
        commitData.getAddedFilesList().size(),
        linkAddedFilesMs,
        commitData.getModifiedFilesList().size(),
        linkModifiedFilesMs,
        commitData.getUnchangedFilesList().size(),
        linkUnchangedFilesMs,
        copyUnchangedFromParentMs,
        deletedPaths.size(),
        unlinkDeletedFilesMs,
        commitData.getTagsList().size(),
        tagsMs,
        finalizeCommitMs,
        elapsedMillis(commitStart));
  }

  private void copyUnchangedFromParent(
      final Session session,
      final CommitData commitData,
      final Commit commit,
      final Set<String> addedPaths,
      final Set<String> modifiedPaths,
      final Set<String> deletedPaths) {
    commitRepository
        .findCommitInternalId(
            session, commitData.getParentCommitId(), commitData.getLandscapeToken())
        .ifPresent(
            gitParentInternalId ->
                repairIncompleteGitParentIfNeeded(session, commitData, gitParentInternalId));

    final Optional<Long> parentCommitInternalId =
        resolveCopySourceCommitInternalId(session, commitData, commit);

    if (parentCommitInternalId.isEmpty()) {
      Log.debugf(
          "Skipping unchanged-file copy for commit %s in repository '%s': "
              + "no parent commit with linked files (git parent=%s)",
          commitData.getCommitId(), commitData.getRepositoryName(), commitData.getParentCommitId());
      return;
    }

    final UnchangedCommitFileCopier.CopyUnchangedFilesFromParentRequest copyRequest =
        new UnchangedCommitFileCopier.CopyUnchangedFilesFromParentRequest(
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            parentCommitInternalId.get(),
            commit.getId(),
            addedPaths,
            modifiedPaths,
            deletedPaths);

    final int parentFileCount =
        commitRepository.countLinkedFileRevisions(session, parentCommitInternalId.get());
    Log.debugf(
        "Copying unchanged files from parent commit id %d (%d linked files) to commit %s "
            + "in repository '%s'",
        parentCommitInternalId.get(),
        parentFileCount,
        commitData.getCommitId(),
        commitData.getRepositoryName());

    unchangedCommitFileCopier.copyFromParentOrThrow(session, copyRequest);
  }

  /**
   * Repairs a git parent that only has explicitly changed files linked (a state produced by earlier
   * async copy bugs). Copies missing unchanged files from the grandparent before the child commit
   * copies from the parent.
   */
  private void repairIncompleteGitParentIfNeeded(
      final Session session, final CommitData commitData, final long gitParentInternalId) {
    final int linkedCount = commitRepository.countLinkedFileRevisions(session, gitParentInternalId);
    if (linkedCount == 0) {
      return;
    }

    final int explicitlyChangedCount =
        commitRepository.countExplicitlyChangedFileLinks(session, gitParentInternalId);
    if (linkedCount > explicitlyChangedCount + INCOMPLETE_PARENT_TOLERANCE) {
      return;
    }

    final Optional<Long> grandparentInternalId =
        commitRepository.findParentCommitInternalId(session, gitParentInternalId);
    if (grandparentInternalId.isEmpty()) {
      return;
    }

    final int grandparentCount =
        commitRepository.countLinkedFileRevisions(session, grandparentInternalId.get());
    if (grandparentCount == 0) {
      return;
    }

    Log.warnf(
        "Git parent commit id %d has %d linked files (%d explicitly changed) for repository '%s'; "
            + "repairing from grandparent id %d (%d files) before persisting commit %s",
        gitParentInternalId,
        linkedCount,
        explicitlyChangedCount,
        commitData.getRepositoryName(),
        grandparentInternalId.get(),
        grandparentCount,
        commitData.getCommitId());

    final Set<String> changedOnParent =
        commitRepository.findExplicitlyChangedFilePaths(session, gitParentInternalId);

    unchangedCommitFileCopier.copyFromParentOrThrow(
        session,
        new UnchangedCommitFileCopier.CopyUnchangedFilesFromParentRequest(
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            grandparentInternalId.get(),
            gitParentInternalId,
            Set.of(),
            changedOnParent,
            Set.of()));
  }

  /**
   * Resolves which commit to copy unchanged files from. Prefer the git parent named in {@link
   * CommitData#getParentCommitId()} when it already has file links, but fall back to the latest
   * commit on the branch that has linked files when the git parent was never persisted or is still
   * empty (e.g. metadata-only or async copy still in flight for a prior commit).
   */
  private Optional<Long> resolveCopySourceCommitInternalId(
      final Session session, final CommitData commitData, final Commit commit) {
    final Optional<Long> gitParentInternalId =
        commitRepository.findCommitInternalId(
            session, commitData.getParentCommitId(), commitData.getLandscapeToken());

    if (gitParentInternalId.isPresent()) {
      if (commitRepository.countLinkedFileRevisions(session, gitParentInternalId.get()) > 0) {
        return gitParentInternalId;
      }
      Log.debugf(
          "Git parent commit %s (id %d) has no linked files yet for repository '%s'",
          commitData.getParentCommitId(),
          gitParentInternalId.get(),
          commitData.getRepositoryName());
    }

    final Optional<Long> latestWithFiles =
        commitRepository.findLatestCommitWithLinkedFilesInternalId(
            session,
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            commitData.getBranchName(),
            commit.getId());

    if (latestWithFiles.isPresent()) {
      Log.debugf(
          "Copying unchanged files from latest linked commit %d instead of git parent %s "
              + "for repository '%s'",
          latestWithFiles.get(), commitData.getParentCommitId(), commitData.getRepositoryName());
    }
    return latestWithFiles;
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }
}
