package net.explorviz.landscape.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
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
import net.explorviz.landscape.repository.CommitMetricsAccumulator;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.ContributorRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.RepositoryRepository;
import net.explorviz.landscape.repository.TagRepository;
import net.explorviz.landscape.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class CommitServiceImpl implements CommitService {

  private static final String NO_PARENT_ID = "NONE";
  @Inject ApplicationRepository applicationRepository;
  @Inject BranchRepository branchRepository;
  @Inject CommitRepository commitRepository;
  @Inject LandscapeRepository landscapeRepository;
  @Inject RepositoryRepository repositoryRepository;
  @Inject FileRevisionRepository fileRevisionRepository;
  @Inject TagRepository tagRepository;
  @Inject ContributorRepository contributorRepository;
  @Inject CommitMetricsAccumulator commitMetricsAccumulator;
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

  public void saveCommitData(final Session session, final CommitData commitData) {
    final Repository repo =
        repositoryRepository
            .findRepositoryByNameAndLandscapeToken(
                session, commitData.getRepositoryName(), commitData.getLandscapeToken())
            .orElseThrow(
                () ->
                    Status.FAILED_PRECONDITION
                        .withDescription("No corresponding state data was sent before.")
                        .asRuntimeException());

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

    fileRevisionRepository.persistCommitFilesInBatches(
        session,
        commitData.getAddedFilesList(),
        commitData.getRepositoryName(),
        commitData.getLandscapeToken(),
        commitData.getCommitId(),
        FileRevisionRepository.CommitFileLinkType.ADDED);

    fileRevisionRepository.persistCommitFilesInBatches(
        session,
        commitData.getModifiedFilesList(),
        commitData.getRepositoryName(),
        commitData.getLandscapeToken(),
        commitData.getCommitId(),
        FileRevisionRepository.CommitFileLinkType.MODIFIED);

    if (!commitData.getParentCommitId().isEmpty()
        && !NO_PARENT_ID.equals(commitData.getParentCommitId())) {
      final Set<String> modifiedPaths =
          commitData.getModifiedFilesList().stream()
              .map(FileIdentifier::getFilePath)
              .collect(Collectors.toCollection(HashSet::new));
      final Set<String> deletedPaths =
          commitData.getDeletedFilesList().stream()
              .map(FileIdentifier::getFilePath)
              .collect(Collectors.toCollection(HashSet::new));

      fileRevisionRepository.copyUnchangedFilesFromParentCommit(
          session,
          new FileRevisionRepository.CopyUnchangedFilesFromParentRequest(
              commitData.getLandscapeToken(),
              commitData.getRepositoryName(),
              commitData.getParentCommitId(),
              commitData.getCommitId(),
              modifiedPaths,
              deletedPaths));
    }

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

    commitMetricsAccumulator.updatePendingForCommit(
        session,
        commitData.getLandscapeToken(),
        commitData.getRepositoryName(),
        commitData.getCommitId());
  }
}
