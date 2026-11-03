package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Repository;
import net.explorviz.landscape.util.otel.SpanFactory;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class RepositoryRepository {

  @Inject Tracer tracer;

  public Optional<Repository> findRepositoryByNameAndLandscapeToken(
      final Session session, final String name, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Repository.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(r:Repository {name: $name})
            OPTIONAL MATCH (r)-[rel:HAS_ROOT]->(n)
            RETURN r, rel, n;
            """,
            Map.of("tokenId", tokenId, "name", name)));
  }

  @WithSpan
  public List<String> fetchAllRepositoryNamesInLandscape(
      final Session session, final String landscapeToken) {
    final String query =
        """
        MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(r:Repository)
        RETURN DISTINCT r.name
        ORDER BY r.name ASC;
        """;

    final Map<String, Serializable> params = Map.of("tokenId", landscapeToken);

    final Span span = SpanFactory.startNeo4jSpan(tracer, "MATCH", query, params);
    final Iterable<String> result = session.query(String.class, query, params);
    span.end();

    return Lists.newArrayList(result);
  }

  public Repository getOrCreateRepository(
      final Session session, final String repoName, final String tokenId) {
    return findRepositoryByNameAndLandscapeToken(session, repoName, tokenId)
        .orElse(new Repository(repoName));
  }
}
