package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Landscape;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class LandscapeRepository {

  private static final String LANDSCAPE_SUBGRAPH_RELATIONSHIPS =
      "CONTAINS>|HAS_ROOT>|HAS_FIRST_PARENT>|HAS_MISC_PARENT>|REPRESENTS>|BELONGS_TO>"
          + "|IS_TAGGED_WITH>|INHERITS>|ADDED>|DELETED>|MODIFIED>|HAS_VERSION>|GENERATES>|USED>"
          + "|DERIVED_FROM>|REFERENCES>";

  private static final int DELETE_BATCH_SIZE = 10_000;
  private static final int DELETE_BATCH_RETRIES = 3;
  private static final int DELETE_PASSES = 3;

  @Inject SessionFactory sessionFactory;

  public Optional<Landscape> findLandscapeByTokenId(final Session session, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Landscape.class,
            "MATCH (l:Landscape {tokenId: $tokenId}) RETURN l;",
            Map.of("tokenId", tokenId)));
  }

  public Optional<Landscape> findLandscapeByTokenId(final String tokenId) {
    final Session session = sessionFactory.openSession();
    return findLandscapeByTokenId(session, tokenId);
  }

  public Landscape getOrCreateLandscape(final Session session, final String tokenId) {
    return findLandscapeByTokenId(session, tokenId).orElse(new Landscape(tokenId));
  }

  /**
   * Deletes all graph data associated with a landscape token, including runtime and static analysis
   * data. Contributors that are no longer linked to any remaining graph data are removed as well;
   * contributors still referenced by other landscapes are kept.
   *
   * <p>The landscape's subgraph is deleted in batches that each commit in their own transaction, so
   * the transaction state of a large landscape never has to fit into the database's transaction
   * memory at once. The {@code Landscape} node itself is deleted last: if a batch fails, the
   * remaining data is still reachable from it and the deletion can simply be repeated.
   *
   * <p>Must not be called inside an open transaction of the given session, as the batches commit
   * independently of it.
   *
   * @throws IllegalStateException if nodes are still left after several deletion passes
   */
  public void deleteLandscapeData(final Session session, final String tokenId) {
    // A batch that still fails after APOC's own retries leaves its nodes reachable from the
    // landscape, so a further pass picks up exactly the nodes that are left.
    String lastFailure = null;
    for (int pass = 1; pass <= DELETE_PASSES; pass++) {
      lastFailure = deleteLandscapeSubgraphInBatches(session, tokenId);
      if (lastFailure == null) {
        break;
      }
      Log.warnf(
          "Pass %d of %d deleting landscape %s did not complete: %s",
          pass, DELETE_PASSES, tokenId, lastFailure);
    }
    if (lastFailure != null) {
      throw new IllegalStateException(
          "Deleting landscape %s failed after %d pass(es): %s"
              .formatted(tokenId, DELETE_PASSES, lastFailure));
    }

    session.query(
        """
        MATCH (l:Landscape {tokenId: $tokenId})
        DETACH DELETE l
        """,
        Map.of("tokenId", tokenId));

    session.query(
        """
        MATCH (c:Contributor)
        WHERE NOT (c)--()
        DETACH DELETE c
        """,
        Map.of());
  }

  /**
   * Runs one batched deletion pass over the landscape's subgraph, excluding the {@code Landscape}
   * node itself.
   *
   * @return {@code null} if every node was deleted, otherwise a description of the failure
   */
  private String deleteLandscapeSubgraphInBatches(final Session session, final String tokenId) {
    // The node list is collected eagerly so that the traversal has finished before the first batch
    // deletes nodes it would otherwise still have to expand.
    final Result result =
        session.query(
            """
            CALL apoc.periodic.iterate(
              'MATCH (l:Landscape {tokenId: $tokenId})
               CALL apoc.path.subgraphNodes(l, {relationshipFilter: $relationshipFilter})
               YIELD node
               WITH l, node WHERE node <> l
               WITH collect(node) AS nodes
               UNWIND nodes AS n
               RETURN n',
              'DETACH DELETE n',
              {
                batchSize: $batchSize,
                parallel: false,
                retries: $retries,
                params: {tokenId: $tokenId, relationshipFilter: $relationshipFilter}
              })
            YIELD total, committedOperations, failedBatches, retries, errorMessages, batch
            RETURN total, committedOperations, failedBatches, retries, errorMessages,
                   batch.errors AS batchErrors
            """,
            Map.of(
                "tokenId",
                tokenId,
                "relationshipFilter",
                LANDSCAPE_SUBGRAPH_RELATIONSHIPS,
                "batchSize",
                DELETE_BATCH_SIZE,
                "retries",
                DELETE_BATCH_RETRIES));

    for (final Map<String, Object> row : result) {
      final long total = ((Number) row.get("total")).longValue();
      final long committed = ((Number) row.get("committedOperations")).longValue();
      final long failedBatches = ((Number) row.get("failedBatches")).longValue();
      if (failedBatches > 0 || committed < total) {
        // Commit failures of whole batches are reported in batch.errors, failures of single
        // operations in errorMessages.
        return ("%d of %d node(s) deleted, %d failed batch(es), %s retries, "
                + "batch errors: %s, operation errors: %s")
            .formatted(
                committed,
                total,
                failedBatches,
                row.get("retries"),
                row.get("batchErrors"),
                row.get("errorMessages"));
      }
    }
    return null;
  }
}
