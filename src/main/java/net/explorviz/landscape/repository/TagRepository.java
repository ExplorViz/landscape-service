package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Tag;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class TagRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Tag> findTagByNameAndRepositoryNameAndLandscapeToken(
      final Session session,
      final String tagName,
      final String repoName,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            Tag.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(t:Tag {name: $name})
            RETURN t;
            """,
            Map.of("name", tagName, "repoName", repoName, "landscapeToken", landscapeToken)));
  }
}
