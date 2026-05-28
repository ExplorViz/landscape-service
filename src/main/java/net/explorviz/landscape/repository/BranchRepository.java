package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Branch;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class BranchRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Branch> findBranchByNameAndRepositoryNameAndLandscapeToken(
      final Session session, final String branchName, final String repoName, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Branch.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(b:Branch {name: $branchName})
            RETURN b;
            """,
            Map.of("tokenId", tokenId, "repoName", repoName, "branchName", branchName)));
  }

  public Optional<Branch> findBranchByNameAndRepositoryNameAndLandscapeToken(
      final String branchName, final String repoName, final String tokenId) {
    final Session session = sessionFactory.openSession();
    return findBranchByNameAndRepositoryNameAndLandscapeToken(
        session, branchName, repoName, tokenId);
  }

  public Branch getOrCreateBranch(
      final Session session, final String branchName, final String repoName, final String tokenId) {
    return findBranchByNameAndRepositoryNameAndLandscapeToken(
            session, branchName, repoName, tokenId)
        .orElse(new Branch(branchName));
  }
}
