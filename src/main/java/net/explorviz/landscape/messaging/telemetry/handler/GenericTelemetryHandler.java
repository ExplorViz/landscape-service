package net.explorviz.landscape.messaging.telemetry.handler;

import java.util.Map;
import net.explorviz.landscape.proto.GenericServiceDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe services that cannot be classified
 * more precisely and writes the corresponding nodes to the graph.
 */
public final class GenericTelemetryHandler {

  private GenericTelemetryHandler() {}

  public static void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasGenericServiceDescriptor()) {
      throw new IllegalArgumentException("Generic descriptor is required");
    }

    final GenericServiceDescriptor descriptor = entity.getGenericServiceDescriptor();

    session.query(
        """
        MERGE (l:Landscape {tokenId: $tokenId})
        MERGE (l)-[:CONTAINS]->(app:Application {name: $serviceName})
        MERGE (app)-[:CONTAINS]->(sc:Scope {name: $scopeName})
        SET sc.telemetryKey = $telemetryKey;
        """,
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "telemetryKey", descriptor.getServiceTelemetryKey(),
            "serviceName", descriptor.getServiceName(),
            "scopeName", entity.getInstrumentationScope()));
  }
}
