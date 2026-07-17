package net.explorviz.landscape.messaging;

import com.google.protobuf.InvalidProtocolBufferException;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.messaging.service.telemetry.CodeTelemetryService;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

/** Receives runtime analysis data extracted from OpenTelemetry signals. */
@ApplicationScoped
public class TelemetryConsumer {

  @Inject CodeTelemetryService codeTelemetryService;

  @Inject SessionFactory sessionFactory;

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
      switch (entity.getEntityDescriptorCase()) { // NOPMD
        case CODE_DESCRIPTOR -> {
          if (!entity.hasCodeDescriptor()) {
            throw new IllegalStateException(
                "Entity descriptor case set to code descriptor, but none present");
          }
          codeTelemetryService.saveEntity(session, entity, entity.getCodeDescriptor());
        }

        default ->
            throw new IllegalStateException(
                "Unhandled entity descriptor type: " + entity.getEntityDescriptorCase());
      }
      tx.commit();
    } catch (Exception e) { // NOPMD
      Log.error("Failed to process code telemetry entity: " + entity, e);
    }
  }
}
