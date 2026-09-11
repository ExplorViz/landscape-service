package net.explorviz.landscape.messaging.telemetry;

import com.google.protobuf.InvalidProtocolBufferException;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;
import net.explorviz.landscape.messaging.telemetry.handler.CodeTelemetryHandler;
import net.explorviz.landscape.messaging.telemetry.handler.GenericTelemetryHandler;
import net.explorviz.landscape.messaging.telemetry.handler.HttpTelemetryHandler;
import net.explorviz.landscape.messaging.telemetry.handler.RpcTelemetryHandler;
import net.explorviz.landscape.messaging.telemetry.handler.TelemetryHandler;
import net.explorviz.landscape.proto.TelemetryEntity;
import net.explorviz.landscape.proto.TelemetryEntity.EntityDescriptorCase;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

/** Receives runtime analysis data extracted from OpenTelemetry signals. */
@ApplicationScoped
public class TelemetryConsumer {

  @Inject SessionFactory sessionFactory;

  private static final Map<EntityDescriptorCase, TelemetryHandler> DESCRIPTOR_TO_HANDLER =
      new EnumMap<>(
          Map.of(
              EntityDescriptorCase.CODE_DESCRIPTOR, CodeTelemetryHandler::saveEntity,
              EntityDescriptorCase.RPC_DESCRIPTOR, RpcTelemetryHandler::saveEntity,
              EntityDescriptorCase.HTTP_DESCRIPTOR, HttpTelemetryHandler::saveEntity,
              EntityDescriptorCase.GENERIC_SERVICE_DESCRIPTOR,
                  GenericTelemetryHandler::saveEntity));

  @Blocking
  @Incoming("telemetry-entities")
  public void consume(final byte[] bytes) {
    final TelemetryEntity entity;
    try {
      entity = TelemetryEntity.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      Log.error("Invalid protocol buffer", e);
      return;
    }

    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      final TelemetryHandler handler = DESCRIPTOR_TO_HANDLER.get(entity.getEntityDescriptorCase());
      if (handler == null) {
        throw new IllegalStateException(
            "Unhandled entity descriptor type: " + entity.getEntityDescriptorCase());
      }
      handler.saveEntity(session, entity);
      tx.commit();
    } catch (Exception e) { // NOPMD
      Log.error("Failed to process code telemetry entity: " + entity, e);
    }
  }
}
