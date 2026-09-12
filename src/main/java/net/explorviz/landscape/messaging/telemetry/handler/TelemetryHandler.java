package net.explorviz.landscape.messaging.telemetry.handler;

import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * A function which is persists telemetry entities with a particular descriptor type to the graph.
 */
@FunctionalInterface
public interface TelemetryHandler {
  /**
   * Stores the passed entity in the database by writing the corresponding nodes to the graph.
   *
   * @param session OGM session object
   * @param entity the entity to persist in the graph database
   * @throws IllegalArgumentException if the entity's descriptor type is incorrect for this handler
   */
  void saveEntity(Session session, TelemetryEntity entity);
}
