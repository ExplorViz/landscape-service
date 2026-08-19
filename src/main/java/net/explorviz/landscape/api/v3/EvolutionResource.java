package net.explorviz.landscape.api.v3;

import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.explorviz.landscape.api.v3.model.BranchDto;
import net.explorviz.landscape.api.v3.model.BranchPointDto;
import net.explorviz.landscape.api.v3.model.CommitAuthorDto;
import net.explorviz.landscape.api.v3.model.CommitNodeDto;
import net.explorviz.landscape.api.v3.model.CommitSampling;
import net.explorviz.landscape.api.v3.model.CommitTreeDto;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.repository.CommitRepository;
import net.explorviz.landscape.repository.RepositoryRepository;
import net.explorviz.landscape.repository.TagRepository;
import net.explorviz.landscape.util.CommitBranchOrderer;
import net.explorviz.landscape.util.CommitFirstParentFilter;
import net.explorviz.landscape.util.CommitTreeFilterer;
import org.jboss.resteasy.reactive.RestPath;
import org.jboss.resteasy.reactive.RestQuery;
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

  @Inject TagRepository tagRepository;

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
      @RestPath final String landscapeToken,
      @RestPath final String repositoryName,
      @RestQuery final Long from,
      @RestQuery final Long to,
      @RestQuery final String sampling,
      @RestQuery final Boolean firstParentOnly) {

    if (from != null && to != null && from > to) {
      throw new BadRequestException("'from' timestamp must be less than or equal to 'to'.");
    }

    final CommitSampling commitSampling = CommitSampling.fromQueryParam(sampling);
    final boolean useFirstParentOnly = firstParentOnly == null || firstParentOnly;
    final Session session = sessionFactory.openSession();

    final Repository repository =
        repositoryRepository
            .findRepositoryByNameAndLandscapeToken(session, repositoryName, landscapeToken)
            .orElseThrow(
                () ->
                    new NotFoundException(
                        "The requested repository does not exist in the database for the given"
                            + " landscape token."));

    final List<Commit> commits =
        commitRepository.findCommitsWithBranchForRepositoryAndLandscapeToken(
            session, landscapeToken, repositoryName);

    final Map<String, List<String>> tagsByCommitHash =
        tagRepository.findTagNamesByCommitHashForRepository(
            session, landscapeToken, repositoryName);

    final Map<String, List<Commit>> branchToCommitMap = new HashMap<>();
    final Map<String, BranchPointDto> branchToBranchPointMap = new HashMap<>();
    populateBranchMappings(commits, branchToCommitMap, branchToBranchPointMap);

    final Map<String, CommitRepository.CommitAuthorProjection> authorsByCommitHash =
        commitRepository.findAuthorsByCommitHashes(
            session,
            landscapeToken,
            repositoryName,
            commits.stream().map(Commit::getHash).collect(Collectors.toSet()));

    final List<BranchDto> branches =
        branchToCommitMap.entrySet().stream()
            .map(
                entry ->
                    toBranchDto(
                        entry,
                        useFirstParentOnly,
                        from,
                        to,
                        commitSampling,
                        tagsByCommitHash,
                        branchToBranchPointMap,
                        authorsByCommitHash))
            .toList();

    return new CommitTreeDto(repositoryName, branches, repository.getRemoteUrl());
  }

  private static void populateBranchMappings(
      final List<Commit> commits,
      final Map<String, List<Commit>> branchToCommitMap,
      final Map<String, BranchPointDto> branchToBranchPointMap) {
    for (final Commit commit : commits) {
      if (commit.getBranch() == null) {
        Log.debugf(
            "Commit with hash %s has no associated branch, will not be included in commit-tree",
            commit.getHash());
        continue;
      }

      final String branchName = commit.getBranch().getName();
      branchToCommitMap.computeIfAbsent(branchName, k -> new ArrayList<>()).add(commit);

      final Set<Commit> parentCommits = parentCommitsOnBranch(commit);
      if (parentCommits.isEmpty()) {
        branchToBranchPointMap.putIfAbsent(branchName, NO_BRANCH_POINT);
        continue;
      }

      final boolean hasParentInSameBranch =
          parentCommits.stream().anyMatch(pc -> branchName.equals(pc.getBranch().getName()));

      if (!hasParentInSameBranch) {
        commit
            .getFirstParentCommit()
            .ifPresent(
                parentCommit ->
                    branchToBranchPointMap.putIfAbsent(
                        branchName,
                        new BranchPointDto(
                            parentCommit.getBranch().getName(), parentCommit.getHash())));
      }
    }
  }

  private static Set<Commit> parentCommitsOnBranch(final Commit commit) {
    return commit.getParentCommits().stream()
        .filter(
            parentCommit -> {
              if (parentCommit.getBranch() == null) {
                Log.debugf(
                    "Parent commit with hash %s has no associated branch, will not be "
                        + "included in commit-tree calculation",
                    parentCommit.getHash());
                return false;
              }
              return true;
            })
        .collect(Collectors.toSet());
  }

  private static BranchDto toBranchDto(
      final Map.Entry<String, List<Commit>> branchEntry,
      final boolean useFirstParentOnly,
      final Long from,
      final Long to,
      final CommitSampling commitSampling,
      final Map<String, List<String>> tagsByCommitHash,
      final Map<String, BranchPointDto> branchToBranchPointMap,
      final Map<String, CommitRepository.CommitAuthorProjection> authorsByCommitHash) {
    List<Commit> branchCommits = CommitBranchOrderer.orderAlongBranch(branchEntry.getValue());
    if (useFirstParentOnly) {
      branchCommits = CommitFirstParentFilter.filterToFirstParentOnly(branchCommits);
    }
    return new BranchDto(
        branchEntry.getKey(),
        CommitTreeFilterer.applyFilters(branchCommits, from, to, commitSampling).stream()
            .map(
                commit ->
                    new CommitNodeDto(
                        commit.getHash(),
                        commit.getCommitDate(),
                        commit.getMetrics(),
                        commit.isHasAccumulatedMetrics(),
                        tagsByCommitHash.getOrDefault(commit.getHash(), List.of()),
                        toAuthorDto(authorsByCommitHash.get(commit.getHash()), commit.getAuthor())))
            .toList(),
        branchToBranchPointMap.get(branchEntry.getKey()));
  }

  private static CommitAuthorDto toAuthorDto(
      final CommitRepository.CommitAuthorProjection projection, final Contributor author) {
    if (projection != null) {
      return new CommitAuthorDto(
          projection.contributorId(),
          projection.gitUsername(),
          projection.githubLogin(),
          projection.email());
    }

    return toAuthorDto(author);
  }

  private static CommitAuthorDto toAuthorDto(final Contributor author) {
    if (author == null) {
      return null;
    }

    return new CommitAuthorDto(
        author.getId(), author.getGitUsername(), author.getGithubLogin(), author.getEmail());
  }
}
