package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Span;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class SpanRepository {

  @Inject ApplicationRepository applicationRepository;

  @Inject FileRevisionRepository fileRevisionRepository;

  @Inject FunctionRepository functionRepository;

  @Inject LandscapeRepository landscapeRepository;

  @Inject SessionFactory sessionFactory;

  @Inject TraceRepository traceRepository;

  public Optional<Span> findSpanById(final Session session, final String spanId) {
    return Optional.ofNullable(
        session.queryForObject(
            Span.class, "MATCH (s:Span {spanId: $spanId}) RETURN s;", Map.of("spanId", spanId)));
  }

  public Span getOrCreateSpan(final Session session, final String spanId) {
    return findSpanById(session, spanId).orElse(new Span(spanId));
  }
}
