package net.explorviz.landscape.messaging.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.repository.LandscapeRepository;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@ApplicationScoped
public class LandscapeDeletionService {

  @Inject LandscapeRepository landscapeRepository;

  @Inject SessionFactory sessionFactory;

  public void deleteLandscapeData(final String landscapeTokenId) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      landscapeRepository.deleteLandscapeData(session, landscapeTokenId);
      tx.commit();
      Log.infof("Deleted landscape data for token %s", landscapeTokenId);
    } catch (Exception e) { // NOPMD
      Log.errorf(e, "Failed to delete landscape data for token %s", landscapeTokenId);
      throw e;
    }
  }
}
