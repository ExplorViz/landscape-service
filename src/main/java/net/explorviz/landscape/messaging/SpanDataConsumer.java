package net.explorviz.landscape.messaging;

import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.explorviz.landscape.messaging.service.SpanPersistenceService;
import net.explorviz.persistence.avro.SpanData;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;
import org.neo4j.ogm.transaction.Transaction;

@ApplicationScoped
public class SpanDataConsumer {

  @Inject SpanPersistenceService spanPersistenceService;

  @Inject SessionFactory sessionFactory;

  @Blocking
  @Incoming("explorviz-spans")
  public void consume(final SpanData spanData) {
    final Session session = sessionFactory.openSession();

    try (Transaction tx = session.beginTransaction()) {
      spanPersistenceService.saveSpanData(session, spanData);
      tx.commit();
    } catch (Exception e) { // NOPMD
      Log.error("Failed to process span: " + spanData.getSpanId(), e);
    }
  }
}
