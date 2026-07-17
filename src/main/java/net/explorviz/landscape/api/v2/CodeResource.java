package net.explorviz.landscape.api.v2;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v2.model.BranchDto;
import net.explorviz.landscape.api.v2.model.BranchPointDto;
import net.explorviz.landscape.api.v2.model.CommitComparisonDto;
import net.explorviz.landscape.api.v2.model.CommitTreeDto;
import net.explorviz.landscape.api.v2.model.EntityMetricsComparison;
import net.explorviz.landscape.api.v2.model.EntityMetricsComparison.ValueComparison;
import net.explorviz.landscape.api.v2.model.landscape.ApplicationDto;
import net.explorviz.landscape.api.v2.model.landscape.LandscapeDto;
import net.explorviz.landscape.api.v2.model.landscape.NodeDto;
import net.explorviz.landscape.api.v2.model.metrics.ApplicationMetricsCodeDto;
import net.explorviz.landscape.api.v2.model.metrics.ClassMetricCodeDto;
import net.explorviz.landscape.api.v2.model.metrics.FileMetricCodeDto;
import net.explorviz.landscape.api.v2.model.metrics.MethodMetricCodeDto;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Clazz;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.repository.ApplicationRepository;
import net.explorviz.landscape.repository.ClazzRepository;
import net.explorviz.landscape.repository.CommitDiffRepository;
import net.explorviz.landscape.repository.CommitDiffRepository.CommitDiffQuery;
import net.explorviz.landscape.repository.CommitDiffRepository.FileComparison;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.FileRevisionRepository;
import net.explorviz.landscape.repository.FunctionRepository;
import net.explorviz.landscape.util.CommitBranchOrderer;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@Path("/v2/code")
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class CodeResource {

  /**
   * Dummy branch point expected by frontend if no branch point exists (e.g. for the main branch).
   */
  private static final BranchPointDto NO_BRANCH_POINT = new BranchPointDto("NONE", "");

  @Inject ApplicationRepository applicationRepository;

  @Inject ClazzRepository clazzRepository;

  @Inject CommitRepository commitRepository;

  @Inject CommitDiffRepository commitDiffRepository;

  @Inject FileRevisionRepository fileRevisionRepository;

  @Inject FunctionRepository functionRepository;

  @Inject SessionFactory sessionFactory;

  @GET
  @Path("/applications/{landscapeToken}")
  @Produces(MediaType.APPLICATION_JSON)
  public List<String> getStaticApplicationNamesForLandscape(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();
    return applicationRepository.findStaticApplicationNamesForLandscapeToken(
        session, landscapeToken);
  }

  @GET
  @Path("/commit-tree/{landscapeToken}/{applicationName}")
  @Produces(MediaType.APPLICATION_JSON)
  public CommitTreeDto getCommitTreeForApplicationAndLandscape(
      @RestPath final String landscapeToken, @RestPath final String applicationName) {
    final Session session = sessionFactory.openSession();

    if (applicationRepository
        .findApplicationByNameAndLandscapeToken(session, applicationName, landscapeToken)
        .isEmpty()) {
      throw new NotFoundException("The requested application does not exist in the database.");
    }

    final List<Commit> commits =
        commitRepository.findCommitsWithBranchForApplicationAndLandscapeToken(
            session, landscapeToken, applicationName);

    final Map<String, List<Commit>> branchToCommitsMap = new HashMap<>();
    final Map<String, BranchPointDto> branchToBranchPointMap = new HashMap<>();

    for (final Commit commit : commits) {

      if (commit.getBranch() == null) {
        Log.debugf(
            "Commit with hash %s has no associated branch, will not be included in commit-tree",
            commit.getHash());
        continue;
      }

      final String branchName = commit.getBranch().getName();

      branchToCommitsMap.computeIfAbsent(branchName, k -> new ArrayList<>()).add(commit);

      final Set<Commit> parentCommits =
          commit.getParentCommits().stream()
              .filter(
                  pc -> {
                    if (pc.getBranch() == null) {
                      Log.debugf(
                          "Parent commit with hash %s has no associated branch, will not be "
                              + "included in commit-tree calculation",
                          pc.getHash());
                      return false;
                    }
                    return true;
                  })
              .collect(Collectors.toSet());

      if (parentCommits.isEmpty()) {
        branchToBranchPointMap.putIfAbsent(branchName, NO_BRANCH_POINT);
        continue;
      }

      // If all parent commits are assigned to a different branch than the current commit, then we
      // treat this as the first commit unique to this branch and therefore create a branch point
      // from the first of the parent commits. Usually, there is only 1 parent commit in this case.
      final boolean hasParentInSameBranch =
          parentCommits.stream().anyMatch(pc -> branchName.equals(pc.getBranch().getName()));

      if (!hasParentInSameBranch) {
        final Optional<Commit> parentCommitOptional = parentCommits.stream().findAny();
        parentCommitOptional.ifPresent(
            parentCommit ->
                branchToBranchPointMap.putIfAbsent(
                    branchName,
                    new BranchPointDto(
                        parentCommit.getBranch().getName(), parentCommit.getHash())));
      }
    }

    final List<BranchDto> branches =
        branchToCommitsMap.entrySet().stream()
            .map(
                e ->
                    new BranchDto(
                        e.getKey(),
                        CommitBranchOrderer.orderAlongBranch(e.getValue()).stream()
                            .map(Commit::getHash)
                            .toList(),
                        branchToBranchPointMap.get(e.getKey())))
            .toList();

    return new CommitTreeDto(applicationName, branches);
  }

  @GET
  @Path("/metrics/{landscapeToken}/{applicationName}/{commitHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public ApplicationMetricsCodeDto getStaticCodeMetricsForApplicationAndCommit(
      @RestPath final String landscapeToken,
      @RestPath final String applicationName,
      @RestPath final String commitHash) {
    final Session session = sessionFactory.openSession();

    final Map<String, FileRevision> appFiles =
        fileRevisionRepository.findStaticFilesWithFqnForApplicationAndCommitAndLandscapeToken(
            session, applicationName, commitHash, landscapeToken);

    final Map<String, Clazz> appClasses =
        clazzRepository.findStaticClassesWithFqnForApplicationAndCommitAndLandscapeToken(
            session, applicationName, commitHash, landscapeToken);

    final Map<String, Function> appFunctions =
        functionRepository.findStaticFunctionsWithFqnForApplicationAndCommitAndLandscapeToken(
            session, applicationName, commitHash, landscapeToken);

    final Map<String, FileMetricCodeDto> fileMetricMap =
        appFiles.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> new FileMetricCodeDto(e.getValue())));

    final Map<String, ClassMetricCodeDto> classMetricMap =
        appClasses.entrySet().stream()
            .collect(
                Collectors.toMap(Map.Entry::getKey, e -> new ClassMetricCodeDto(e.getValue())));

    final Map<String, MethodMetricCodeDto> methodMetricMap =
        appFunctions.entrySet().stream()
            .collect(
                Collectors.toMap(Map.Entry::getKey, e -> new MethodMetricCodeDto(e.getValue())));

    return new ApplicationMetricsCodeDto(fileMetricMap, classMetricMap, methodMetricMap);
  }

  @GET
  @Path("/structure/{landscapeToken}/{applicationName}/{firstCommitHash}-{secondCommitHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public LandscapeDto getStaticStructureForApplicationAndTwoCommits(
      @RestPath final String landscapeToken,
      @RestPath final String applicationName,
      @RestPath final String firstCommitHash,
      @RestPath final String secondCommitHash) {
    final Session session = sessionFactory.openSession();

    final List<Application> applications =
        applicationRepository.fetchApplicationsHydratedForTwoCommits(
            session, landscapeToken, firstCommitHash, secondCommitHash);

    final List<ApplicationDto> applicationDtoList =
        applications.stream()
            .filter(a -> applicationName.equals(a.getName()))
            .map(ApplicationDto::new)
            .toList();

    final NodeDto node = new NodeDto("", "", applicationDtoList);
    return new LandscapeDto(landscapeToken, List.of(node), List.of());
  }

  @GET
  @Path("/structure/{landscapeToken}/{applicationName}/{commitHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public LandscapeDto getStaticStructureForApplicationAndSingleCommit(
      @RestPath final String landscapeToken,
      @RestPath final String applicationName,
      @RestPath final String commitHash) {
    // Re-use query for two-commit case by supplying same hash twice
    return getStaticStructureForApplicationAndTwoCommits(
        landscapeToken, applicationName, commitHash, commitHash);
  }

  @GET
  @Path(
      "/commit-comparison/{landscapeToken}/{applicationName}/{firstCommitHash}-{secondCommitHash}")
  @Produces(MediaType.APPLICATION_JSON)
  public CommitComparisonDto getCommitComparison(
      @RestPath final String landscapeToken,
      @RestPath final String applicationName,
      @RestPath final String firstCommitHash,
      @RestPath final String secondCommitHash) {
    final Session session = sessionFactory.openSession();
    final CommitDiffQuery diffQuery =
        new CommitDiffQuery(landscapeToken, applicationName, firstCommitHash, secondCommitHash);

    final List<String> modifiedFiles =
        commitDiffRepository.findModifiedFileFqns(session, diffQuery);

    final List<String> addedFiles = commitDiffRepository.findAddedFileFqns(session, diffQuery);

    final List<String> deletedFiles = commitDiffRepository.findDeletedFileFqns(session, diffQuery);

    final List<String> addedPackages =
        commitDiffRepository.findAddedDirectoryFqns(session, diffQuery);

    final List<String> deletedPackages =
        commitDiffRepository.findDeletedDirectoryFqns(session, diffQuery);

    final List<FileComparison> metricResults =
        commitDiffRepository.findFilesWithCounterpart(session, diffQuery);

    final List<EntityMetricsComparison> entityMetricsComparisons =
        metricResults.stream()
            .map(
                result -> {
                  final Map<String, Double> newMetrics = result.fileFirstCommit().getMetrics();
                  final Map<String, Double> oldMetrics =
                      (result.fileSecondCommit() != null)
                          ? result.fileSecondCommit().getMetrics()
                          : Map.of();

                  final Map<String, ValueComparison> metricComparisons =
                      newMetrics.entrySet().stream()
                          .collect(
                              Collectors.toMap(
                                  Map.Entry::getKey,
                                  entry -> {
                                    final String oldValue =
                                        Objects.toString(oldMetrics.get(entry.getKey()), null);
                                    final String newValue = entry.getValue().toString();
                                    return new ValueComparison(oldValue, newValue);
                                  }));

                  return new EntityMetricsComparison(result.fileFqn(), metricComparisons);
                })
            .toList();

    return new CommitComparisonDto(
        modifiedFiles,
        addedFiles,
        deletedFiles,
        addedPackages,
        deletedPackages,
        entityMetricsComparisons);
  }
}
