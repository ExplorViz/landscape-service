package net.explorviz.landscape.api.v3;

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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.BranchDto;
import net.explorviz.landscape.api.v3.model.BranchPointDto;
import net.explorviz.landscape.api.v3.model.CommitNodeDto;
import net.explorviz.landscape.api.v3.model.CommitTreeDto;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.RepositoryRepository;
import org.jboss.resteasy.reactive.RestPath;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/** Contains endpoints concerning git repository analysis. */
@Path("/v3/landscapes/{landscapeToken}")
public class EvolutionResource {

  /**
   * Dummy branch point expected by frontend if no branch point exists (e.g. for the main branch).
   */
  private static final BranchPointDto NO_BRANCH_POINT = new BranchPointDto("NONE", "");

  @Inject SessionFactory sessionFactory;

  @Inject CommitRepository commitRepository;

  @Inject RepositoryRepository repositoryRepository;

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  @Path("/repositories")
  public List<String> getRepositoryNames(@RestPath final String landscapeToken) {
    final Session session = sessionFactory.openSession();
    return repositoryRepository.fetchAllRepositoryNamesInLandscape(session, landscapeToken);
  }

  @GET
  @Path("/commit-tree/{repositoryName}")
  @Produces(MediaType.APPLICATION_JSON)
  public CommitTreeDto getCommitTreeForRepositoryAndLandscape(
      @RestPath final String landscapeToken, @RestPath final String repositoryName) {
    final Session session = sessionFactory.openSession();

    if (repositoryRepository
        .findRepositoryByNameAndLandscapeToken(session, repositoryName, landscapeToken)
        .isEmpty()) {
      throw new NotFoundException(
          "The requested repository does not exist in the database for the given landscape token.");
    }

    final List<Commit> commits =
        commitRepository.findCommitsWithBranchForRepositoryAndLandscapeToken(
            session, landscapeToken, repositoryName);

    final Map<String, List<CommitNodeDto>> branchToCommitMap = new HashMap<>();
    final Map<String, BranchPointDto> branchToBranchPointMap = new HashMap<>();

    for (final Commit commit : commits) {

      if (commit.getBranch() == null) {
        Log.warnf(
            "Commit with hash %s has no associated branch, will not be included in commit-tree",
            commit.getHash());
        continue;
      }

      final String branchName = commit.getBranch().getName();

      branchToCommitMap
          .computeIfAbsent(branchName, k -> new ArrayList<>())
          .add(
              new CommitNodeDto(
                  commit.getHash(), commit.getMetrics(), commit.isHasAccumulatedMetrics()));

      final Set<Commit> parentCommits =
          commit.getParentCommits().stream()
              .filter(
                  pc -> {
                    if (pc.getBranch() == null) {
                      Log.warnf(
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
        branchToCommitMap.entrySet().stream()
            .map(
                e ->
                    new BranchDto(e.getKey(), e.getValue(), branchToBranchPointMap.get(e.getKey())))
            .toList();

    return new CommitTreeDto(repositoryName, branches);
  }
}
