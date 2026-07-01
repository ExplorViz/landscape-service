package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import net.explorviz.landscape.repository.CommitStaleFileRevisionUnlinker;
import net.explorviz.landscape.repository.ContributorRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.ParentCommitInheritancePolicy;
import net.explorviz.landscape.repository.PendingCommitContextRegistry;
import net.explorviz.landscape.repository.RepositoryCommitPersistenceCoordinator;
import net.explorviz.landscape.repository.RepositoryRepository;
import net.explorviz.landscape.repository.TagRepository;
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class CommitServiceImpl implements CommitService {

  private static final String NO_PARENT_ID = "NONE";

  @Inject ApplicationRepository applicationRepository;
  @Inject BranchRepository branchRepository;
  @Inject CommitRepository commitRepository;
  @Inject LandscapeRepository landscapeRepository;
  @Inject RepositoryRepository repositoryRepository;
  @Inject FileRevisionRepository fileRevisionRepository;
  @Inject CommitDeletedFileUnlinker commitDeletedFileUnlinker;
  @Inject CommitStaleFileRevisionUnlinker commitStaleFileRevisionUnlinker;
  @Inject CommitMetricsAccumulator commitMetricsAccumulator;
  @Inject PendingCommitContextRegistry pendingCommitContextRegistry;
  @Inject TagRepository tagRepository;
  @Inject ContributorRepository contributorRepository;
  @Inject SessionFactory sessionFactory;
  @Inject RepositoryCommitPersistenceCoordinator commitPersistenceCoordinator;

  @Blocking
  @Override
  public Uni<Empty> persistCommit(final CommitData request) {
    try {
      commitPersistenceCoordinator.runExclusive(
          request.getLandscapeToken(),
          request.getRepositoryName(),
          () -> {
            final Session session = sessionFactory.openSession();
            try (Transaction tx = session.beginTransaction()) {
              saveCommitData(session, request);
              tx.commit();
            } finally {
              session.clear();
            }
            return Empty.getDefaultInstance();
          });
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    }
  }

  @SuppressWarnings({"PMD.NcssCount", "PMD.CyclomaticComplexity", "PMD.CognitiveComplexity"})
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
    session.save(List.of(repo, branch, commit));
    final long setupCommitMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final CommitFilePersistenceContext fileContext =
        fileRevisionRepository.createFilePersistenceContext(
            session,
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            commitData.getCommitId(),
            commit.getId());
    final long createFileContextMs = elapsedMillis(stepStart);

    final boolean requirePersistedParent =
        ParentCommitInheritancePolicy.requiresPersistedParent(commitData);
    final boolean deferFileStubs = CommitFileStubPolicy.defersFileStubCreation(commitData);
    long linkAddedFilesMs = 0;
    long linkModifiedFilesMs = 0;
    long linkUnchangedFilesMs = 0;
    long copyUnchangedFromParentMs = 0;
    long unlinkStaleRevisionsMs = 0;

    final Set<String> addedPaths =
        commitData.getAddedFilesList().stream()
            .map(FileIdentifier::getFilePath)
            .collect(Collectors.toCollection(HashSet::new));
    final Set<String> modifiedPaths =
        commitData.getModifiedFilesList().stream()
            .map(FileIdentifier::getFilePath)
            .collect(Collectors.toCollection(HashSet::new));
    final Set<String> deletedPaths =
        commitData.getDeletedFilesList().stream()
            .map(FileIdentifier::getFilePath)
            .collect(Collectors.toCollection(HashSet::new));

    stepStart = System.nanoTime();
    if (deferFileStubs) {
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

      if (!commitData.getUnchangedFilesList().isEmpty()) {
        stepStart = System.nanoTime();
        fileRevisionRepository.persistCommitFilesInBatches(
            session,
            fileContext,
            commitData.getUnchangedFilesList(),
            FileRevisionRepository.CommitFileLinkType.CONTAINS);
        linkUnchangedFilesMs = elapsedMillis(stepStart);
      }

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
                    requirePersistedParent));
        copyUnchangedFromParentMs = elapsedMillis(stepStart);

        if (copiedFromParent > 0 && (!addedPaths.isEmpty() || !modifiedPaths.isEmpty())) {
          stepStart = System.nanoTime();
          commitStaleFileRevisionUnlinker.unlinkStaleRevisionsAtChangedPaths(
              session, commit.getId(), modifiedPaths, addedPaths);
          unlinkStaleRevisionsMs = elapsedMillis(stepStart);
        }
      }
    }

    stepStart = System.nanoTime();
    if (!deferFileStubs && !deletedPaths.isEmpty()) {
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
    linkParentCommits(session, repo, commit, commitData);
    final long linkParentCommitsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    session.save(List.of(repo, branch, commit));
    final long finalizeCommitMs = elapsedMillis(stepStart);

    if (!deferFileStubs) {
      commitMetricsAccumulator.updatePendingForCommit(
          session,
          commitData.getLandscapeToken(),
          commitData.getRepositoryName(),
          commitData.getCommitId());
    }

    Log.infof(
        "persistCommit(commit=%s, repo='%s', deferFileStubs=%s): resolveRepository=%dms,"
            + " setupCommit=%dms, createFileContext=%dms, linkAddedFiles(%d)=%dms,"
            + " linkModifiedFiles(%d)=%dms, linkUnchangedFiles(%d)=%dms,"
            + " copyUnchangedFromParent=%dms, unlinkStaleRevisions(%d)=%dms,"
            + " unlinkDeletedFiles(%d)=%dms, tags(%d)=%dms, linkParentCommits=%dms,"
            + " finalizeCommit=%dms, total=%dms",
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
        addedPaths.size() + modifiedPaths.size(),
        unlinkStaleRevisionsMs,
        deletedPaths.size(),
        unlinkDeletedFilesMs,
        commitData.getTagsList().size(),
        tagsMs,
        linkParentCommitsMs,
        finalizeCommitMs,
        elapsedMillis(commitStart));
  }

  private void linkParentCommits(
      final Session session,
      final Repository repo,
      final Commit commit,
      final CommitData commitData) {
    final List<String> parentCommitIds = resolveParentCommitIds(commitData);
    if (parentCommitIds.isEmpty()) {
      return;
    }

    final List<Object> entitiesToSave = new ArrayList<>();
    entitiesToSave.add(repo);
    entitiesToSave.add(commit);
    for (final String parentCommitId : parentCommitIds) {
      final Commit parentCommit =
          commitRepository.getOrCreateCommit(
              session, parentCommitId, commitData.getLandscapeToken());
      commit.addParentCommit(parentCommit);
      repo.addCommit(parentCommit);
      entitiesToSave.add(parentCommit);
    }
    session.save(entitiesToSave);
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private static List<String> resolveParentCommitIds(final CommitData commitData) {
    if (!commitData.getParentCommitIdsList().isEmpty()) {
      return commitData.getParentCommitIdsList().stream()
          .filter(id -> !id.isBlank() && !NO_PARENT_ID.equals(id))
          .distinct()
          .toList();
    }

    if (commitData.getParentCommitId().isBlank()
        || NO_PARENT_ID.equals(commitData.getParentCommitId())) {
      return List.of();
    }

    return List.of(commitData.getParentCommitId());
  }
}
