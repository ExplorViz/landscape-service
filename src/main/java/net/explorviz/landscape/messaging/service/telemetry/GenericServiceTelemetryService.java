package net.explorviz.landscape.messaging.service.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import net.explorviz.landscape.proto.GenericServiceDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

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
        SET app.telemetryKey = $telemetryKey;
        """,
        Map.of(
            "tokenId", entity.getLandscapeTokenId(),
            "telemetryKey", descriptor.getServiceTelemetryKey(),
            "serviceName", descriptor.getServiceName()));
  }
}
