package net.explorviz.persistence.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.explorviz.persistence.ogm.Branch;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.Contributor;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.ogm.Tag;
import net.explorviz.persistence.proto.CommitData;
import net.explorviz.persistence.proto.CommitService;
import net.explorviz.persistence.proto.FileIdentifier;
import net.explorviz.persistence.repository.ApplicationRepository;
import net.explorviz.persistence.repository.BranchRepository;
import net.explorviz.persistence.repository.CommitRepository;
import net.explorviz.persistence.repository.ContributorRepository;
import net.explorviz.persistence.repository.FileRevisionRepository;
import net.explorviz.persistence.repository.LandscapeRepository;
import net.explorviz.persistence.repository.RepositoryRepository;
import net.explorviz.persistence.repository.TagRepository;
import net.explorviz.persistence.util.GrpcExceptionMapper;
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
  @Inject SessionFactory sessionFactory;

  @Blocking
  @Override
  public Uni<Empty> persistCommit(final CommitData request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      saveCommitData(session, request);
      tx.commit();
      return Uni.createFrom().item(Empty.getDefaultInstance());
    } catch (Exception e) { // NOPMD - intentional: Handling in GGrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
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

    commitData
        .getAddedFilesList()
        .forEach(
            f -> {
              final FileRevision r =
                  fileRevisionRepository.createFileStructureFromStaticData(
                      session,
                      f,
                      commitData.getRepositoryName(),
                      commitData.getLandscapeToken(),
                      commit);
              commit.addAddedFileRevision(r);
            });

    commitData
        .getModifiedFilesList()
        .forEach(
            f -> {
              final FileRevision r =
                  fileRevisionRepository.createFileStructureFromStaticData(
                      session,
                      f,
                      commitData.getRepositoryName(),
                      commitData.getLandscapeToken(),
                      commit);
              commit.addModifiedFileRevision(r);
            });

    if (!commitData.getParentCommitId().isEmpty()
        && !NO_PARENT_ID.equals(commitData.getParentCommitId())) {
      final Map<String, FileRevision> parentFiles =
          fileRevisionRepository.findStaticFilesWithFqnForRepositoryAndCommitAndLandscapeToken(
              session,
              commitData.getRepositoryName(),
              commitData.getParentCommitId(),
              commitData.getLandscapeToken());

      final List<String> modifiedPaths =
          commitData.getModifiedFilesList().stream()
              .map(FileIdentifier::getFilePath)
              .collect(Collectors.toList());
      final List<String> deletedPaths =
          commitData.getDeletedFilesList().stream()
              .map(FileIdentifier::getFilePath)
              .collect(Collectors.toList());

      parentFiles.forEach(
          (path, fileRevision) -> {
            if (!modifiedPaths.contains(path) && !deletedPaths.contains(path)) {
              commit.addFileRevision(fileRevision);
            }
          });
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
  }
}
