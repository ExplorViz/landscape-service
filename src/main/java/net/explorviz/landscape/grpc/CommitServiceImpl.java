package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.ogm.Tag;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.CommitService;
import net.explorviz.landscape.repository.ApplicationRepository;
import net.explorviz.landscape.repository.BranchRepository;
import net.explorviz.landscape.repository.CommitDeletedFileUnlinker;
import net.explorviz.landscape.repository.CommitFileLinkTimings;
import net.explorviz.landscape.repository.CommitFileLinker;
import net.explorviz.landscape.repository.CommitFilePersistenceContext;
import net.explorviz.landscape.repository.CommitMetricsAccumulator;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.ContributorRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.PersistCommitTimingLogger;
import net.explorviz.landscape.repository.PersistCommitTimingReport;
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
  @Inject CommitFileLinker commitFileLinker;
  @Inject CommitMetricsAccumulator commitMetricsAccumulator;
  @Inject PersistCommitTimingLogger persistCommitTimingLogger;
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

  public void saveCommitData(final Session session, final CommitData commitData) {
    final long commitStart = System.nanoTime();

    final Repository repo = resolveRepository(session, commitData);
    final long resolveRepositoryMs = elapsedMillis(commitStart);

    final long setupStart = System.nanoTime();
    final Branch branch = setupBranch(session, commitData, repo);
    final Commit commit = setupCommit(session, commitData, repo, branch);
    final long setupCommitMs = elapsedMillis(setupStart);

    final long createFileContextStart = System.nanoTime();
    final CommitFilePersistenceContext fileContext =
        fileRevisionRepository.createFilePersistenceContext(
            session,
            commitData.getLandscapeToken(),
            commitData.getRepositoryName(),
            commitData.getCommitId(),
            commit.getId());
    final long createFileContextMs = elapsedMillis(createFileContextStart);

    final CommitFileLinkTimings fileLinkTimings =
        commitFileLinker.linkCommitFiles(session, commitData, commit, fileContext);

    final long unlinkDeletedStart = System.nanoTime();
    if (fileLinkTimings.shouldUnlinkDeletedFiles() && !fileLinkTimings.deletedPaths().isEmpty()) {
      commitDeletedFileUnlinker.unlinkDeletedFilesFromCommit(
          session, commit.getId(), fileLinkTimings.deletedPaths());
    }
    final long unlinkDeletedFilesMs = elapsedMillis(unlinkDeletedStart);

    final long verifyCacheStart = System.nanoTime();
    if (!fileLinkTimings.metadataOnlyCommit()) {
      verifyCommitFileCacheUnlessDeferred(session, commit, fileLinkTimings.deferFileStubs());
    }
    final long verifyCacheMs = elapsedMillis(verifyCacheStart);

    final long tagsStart = System.nanoTime();
    applyCommitTags(session, commitData, repo, commit);
    final long tagsMs = elapsedMillis(tagsStart);

    final long linkParentCommitsStart = System.nanoTime();
    linkParentCommits(session, repo, commit, commitData);
    final long linkParentCommitsMs = elapsedMillis(linkParentCommitsStart);

    final long finalizeCommitStart = System.nanoTime();
    session.save(List.of(repo, branch, commit));
    final long finalizeCommitMs = elapsedMillis(finalizeCommitStart);

    if (fileLinkTimings.shouldUpdatePendingMetrics()) {
      commitMetricsAccumulator.updatePendingForCommit(
          session,
          commitData.getLandscapeToken(),
          commitData.getRepositoryName(),
          commitData.getCommitId());
    }

    persistCommitTimingLogger.log(
        commitData,
        fileLinkTimings,
        new PersistCommitTimingReport(
            resolveRepositoryMs,
            setupCommitMs,
            createFileContextMs,
            unlinkDeletedFilesMs,
            verifyCacheMs,
            tagsMs,
            linkParentCommitsMs,
            finalizeCommitMs,
            elapsedMillis(commitStart)));
  }

  private Repository resolveRepository(final Session session, final CommitData commitData) {
    return repositoryRepository
        .findRepositoryByNameAndLandscapeToken(
            session, commitData.getRepositoryName(), commitData.getLandscapeToken())
        .orElseThrow(
            () ->
                Status.FAILED_PRECONDITION
                    .withDescription("No corresponding state data was sent before.")
                    .asRuntimeException());
  }

  private Branch setupBranch(
      final Session session, final CommitData commitData, final Repository repo) {
    final Branch branch =
        branchRepository.getOrCreateBranch(
            session,
            commitData.getBranchName(),
            commitData.getRepositoryName(),
            commitData.getLandscapeToken());
    repo.addBranch(branch);
    return branch;
  }

  private Commit setupCommit(
      final Session session,
      final CommitData commitData,
      final Repository repo,
      final Branch branch) {
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
    return commit;
  }

  private void applyCommitTags(
      final Session session,
      final CommitData commitData,
      final Repository repo,
      final Commit commit) {
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
  }

  /**
   * Marks the commit's in-memory file cache as an O(1) copy source for its future children once all
   * of its own file-linking steps have completed, provided file stubs were not deferred.
   */
  private void verifyCommitFileCacheUnlessDeferred(
      final Session session, final Commit commit, final boolean deferFileStubs) {
    if (!deferFileStubs) {
      fileRevisionRepository.verifyAndCacheCommitCompleteness(session, commit.getId());
    }
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
