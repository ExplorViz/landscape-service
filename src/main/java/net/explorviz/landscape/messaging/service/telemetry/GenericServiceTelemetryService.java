package net.explorviz.landscape.messaging.service.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import net.explorviz.landscape.proto.GenericServiceDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe services that cannot be classified
 * more precisely and writes the corresponding nodes to the graph.
 */
@ApplicationScoped
public class GenericServiceTelemetryService {

  public void saveEntity(
      final Session session,
      final TelemetryEntity entity,
      final GenericServiceDescriptor descriptor) {

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
