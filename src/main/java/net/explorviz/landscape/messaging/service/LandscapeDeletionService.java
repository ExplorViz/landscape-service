package net.explorviz.landscape.messaging.service;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.repository.DirectoryIdCache;
import net.explorviz.landscape.repository.LandscapeRepository;
import net.explorviz.landscape.repository.PendingMetricsCommitTracker;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class LandscapeDeletionService {

  @Inject LandscapeRepository landscapeRepository;

  @Inject DirectoryIdCache directoryIdCache;

  @Inject PendingMetricsCommitTracker pendingMetricsCommitTracker;

  @Inject SessionFactory sessionFactory;

  public void deleteLandscapeData(final String landscapeTokenId) {
    final Session session = sessionFactory.openSession();

    // Deliberately not wrapped in a transaction: the deletion commits in batches.
    try {
      // Deleted directories must not be resolved from memory by a later analysis.
      directoryIdCache.clear();
      pendingMetricsCommitTracker.invalidateLandscape(landscapeTokenId);
      landscapeRepository.deleteLandscapeData(session, landscapeTokenId);
      Log.infof("Deleted landscape data for token %s", landscapeTokenId);
    } catch (Exception e) {
      Log.errorf(e, "Failed to delete landscape data for token %s", landscapeTokenId);
      throw e;
    }
  }
}
