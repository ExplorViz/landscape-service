package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Landscape;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class LandscapeRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Landscape> findLandscapeByTokenId(final Session session, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Landscape.class,
            "MATCH (l:Landscape {tokenId: $tokenId}) RETURN l;",
            Map.of("tokenId", tokenId)));
  }

  public Optional<Landscape> findLandscapeByTokenId(final String tokenId) {
    final Session session = sessionFactory.openSession();
    return findLandscapeByTokenId(session, tokenId);
  }

  public Landscape getOrCreateLandscape(final Session session, final String tokenId) {
    return findLandscapeByTokenId(session, tokenId).orElse(new Landscape(tokenId));
  }

  /**
   * Deletes all graph data associated with a landscape token, including runtime and static analysis
   * data. Contributors that are no longer linked to any remaining graph data are removed as well;
   * contributors still referenced by other landscapes are kept.
   */
  public void deleteLandscapeData(final Session session, final String tokenId) {
    session.query(
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        CALL apoc.path.subgraphAll(l, {
          relationshipFilter: "CONTAINS>|HAS_ROOT>|HAS_PARENT>|REPRESENTS>|BELONGS_TO>|IS_TAGGED_WITH>|INHERITS>|ADDED>|DELETED>|MODIFIED>|HAS_VERSION>|GENERATES>|USED>|DERIVED_FROM>|REFERENCES>"
        })
        YIELD nodes
        UNWIND nodes AS n
        DETACH DELETE n
        """,
        Map.of("tokenId", tokenId));

    session.query(
        """
        MATCH (c:Contributor)
        WHERE NOT (c)--()
        DETACH DELETE c
        """,
        Map.of());
  }
}
