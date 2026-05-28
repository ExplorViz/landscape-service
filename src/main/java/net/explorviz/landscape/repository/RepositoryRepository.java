package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Repository;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class RepositoryRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Repository> findRepositoryByNameAndLandscapeToken(
      final Session session, final String name, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Repository.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(r:Repository {name: $name})
            MATCH (r)-[rel]->(n)
            RETURN r, rel, n;
            """,
            Map.of("tokenId", tokenId, "name", name)));
  }

  public List<String> fetchAllRepositoryNamesInLandscape(
      final Session session, final String landscapeToken) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(r:Repository)
            RETURN DISTINCT r.name
            ORDER BY r.name ASC;
            """,
            Map.of("tokenId", landscapeToken)));
  }

  public Repository getOrCreateRepository(
      final Session session, final String repoName, final String tokenId) {
    return findRepositoryByNameAndLandscapeToken(session, repoName, tokenId)
        .orElse(new Repository(repoName));
  }
}
