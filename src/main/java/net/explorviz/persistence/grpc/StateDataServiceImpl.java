package net.explorviz.persistence.grpc;

import io.quarkus.grpc.GrpcService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.explorviz.persistence.ogm.Application;
import net.explorviz.persistence.ogm.Branch;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.Directory;
import net.explorviz.persistence.ogm.Landscape;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.proto.StateData;
import net.explorviz.persistence.proto.StateDataRequest;
import net.explorviz.persistence.proto.StateDataService;
import net.explorviz.persistence.repository.ApplicationRepository;
import net.explorviz.persistence.repository.BranchRepository;
import net.explorviz.persistence.repository.CommitRepository;
import net.explorviz.persistence.repository.DirectoryRepository;
import net.explorviz.persistence.repository.LandscapeRepository;
import net.explorviz.persistence.repository.RepositoryRepository;
import net.explorviz.persistence.util.GrpcExceptionMapper;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@GrpcService
public class StateDataServiceImpl implements StateDataService {

  @Inject SessionFactory sessionFactory;

  @Inject ApplicationRepository applicationRepository;

  @Inject BranchRepository branchRepository;

  @Inject CommitRepository commitRepository;

  @Inject DirectoryRepository directoryRepository;

  @Inject LandscapeRepository landscapeRepository;

  @Inject RepositoryRepository repositoryRepository;

  @Blocking
  @Override
  public Uni<StateData> getStateData(final StateDataRequest request) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      saveStateData(session, request);
      tx.commit();

      final StateData.Builder stateDataBuilder = StateData.newBuilder();
      final String commitId =
          commitRepository
              .findLatestFullyPersistedCommit(
                  session,
                  request.getRepositoryName(),
                  request.getLandscapeToken(),
                  request.getBranchName(),
                  new ArrayList<>(request.getApplicationPathsMap().keySet()))
              .map(Commit::getHash)
              .orElse("");
      stateDataBuilder.setCommitId(commitId);

      return Uni.createFrom().item(stateDataBuilder.build());
    } catch (Exception e) { // NOPMD - intentional: Handling in GrpcExceptionMapper
      return Uni.createFrom().failure(GrpcExceptionMapper.mapToGrpcException(e, request));
    }
  }

  public void saveStateData(final Session session, final StateDataRequest stateData) {
    final Landscape landscape =
        landscapeRepository.getOrCreateLandscape(session, stateData.getLandscapeToken());

    final Repository repository =
        repositoryRepository.getOrCreateRepository(
            session, stateData.getRepositoryName(), stateData.getLandscapeToken());
    landscape.addRepository(repository);

    final Branch branch =
        branchRepository.getOrCreateBranch(
            session,
            stateData.getBranchName(),
            stateData.getRepositoryName(),
            stateData.getLandscapeToken());

    repository.addBranch(branch);

    if (repository.getRootDirectory() == null) {
      final Directory repoRootDirectory = new Directory(stateData.getRepositoryName());
      repository.setRootDirectory(repoRootDirectory);
    }

    session.save(List.of(repository, landscape));

    stateData
        .getApplicationPathsMap()
        .forEach(
            (final String applicationName, final String applicationPath) -> {
              final Application application =
                  applicationRepository
                      .findApplicationByNameAndLandscapeToken(
                          session, applicationName, stateData.getLandscapeToken())
                      .orElse(new Application(applicationName));
              landscape.addApplication(application);

              final Directory applicationRootDirectory =
                  directoryRepository.createDirectoryStructureAndReturnLastDirStaticData(
                      session,
                      (repository.getName() + "/" + applicationPath).split("/"),
                      stateData.getRepositoryName(),
                      stateData.getLandscapeToken());

              final Directory existingAppRoot = application.getRootDirectory();
              if (existingAppRoot != null
                  && !Objects.equals(existingAppRoot.getId(), applicationRootDirectory.getId())) {

                if (Application.ROOT_NAME_PLACEHOLDER_RUNTIME.equals(existingAppRoot.getName())) {
                  directoryRepository.mergeDirectories(
                      session, existingAppRoot.getId(), applicationRootDirectory.getId());
                } else {
                  throw new IllegalArgumentException(
                      "Application \""
                          + applicationName
                          + "\" already exists with different root directory path. ID of existing "
                          + "application root: "
                          + existingAppRoot.getId());
                }
              }

              application.setRootDirectory(applicationRootDirectory);
              session.save(application);
              session.clear();
            });

    session.save(List.of(repository, landscape));
  }
}
