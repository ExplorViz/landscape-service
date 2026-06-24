package net.explorviz.landscape.messaging;

import com.google.protobuf.InvalidProtocolBufferException;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.messaging.service.SpanPersistenceService;
import net.explorviz.landscape.proto.ParsedSpan;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@ApplicationScoped
public class SpanConsumer {

  @Inject SpanPersistenceService spanPersistenceService;

  @Inject SessionFactory sessionFactory;

  @Blocking
  @Incoming("spans-parsed")
  public void consume(final byte[] bytes) {
    final ParsedSpan span;
    try {
      span = ParsedSpan.parseFrom(bytes);
    } catch (InvalidProtocolBufferException e) {
      Log.error("Invalid protocol buffer", e);
      return;
    }

    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      spanPersistenceService.saveSpan(session, span);
      tx.commit();
    } catch (Exception e) { // NOPMD
      Log.error("Failed to process span: " + span.getSpanId(), e);
    }
  }
}
