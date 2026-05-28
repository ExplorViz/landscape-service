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
}
