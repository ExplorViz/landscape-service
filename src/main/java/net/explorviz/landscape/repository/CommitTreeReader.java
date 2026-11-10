package net.explorviz.landscape.repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.Branch;
import net.explorviz.landscape.ogm.Commit;
import org.neo4j.ogm.session.Session;

/** Reads commit-tree metadata without hydrating the repository's OGM entity graph. */
final class CommitTreeReader {

  private static final String METRIC_PREFIX = "metrics.";

  private CommitTreeReader() {}

  static List<Commit> read(
      final Session session, final String landscapeToken, final String repositoryName) {
    final Map<String, Commit> commitsByHash = new HashMap<>();
    final Map<String, Branch> branchesByName = new HashMap<>();
    final List<Commit> commits = new ArrayList<>();
    session
        .query(
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
            MATCH (repo)-[:CONTAINS]->(c:Commit)-[:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[parentRel:HAS_FIRST_PARENT|HAS_MISC_PARENT]->(parent:Commit)
            OPTIONAL MATCH (parent)-[:BELONGS_TO]->(pb:Branch)
            WITH c, b, collect(parent { .hash, branch: pb.name,
              firstParent: type(parentRel) = 'HAS_FIRST_PARENT' }) AS parents
            RETURN properties(c) AS properties, b.name AS branch, parents
            ORDER BY c.authorDate ASC
            """,
            Map.of("tokenId", landscapeToken, "repoName", repositoryName))
        .forEach(row -> addCommit(row, commitsByHash, branchesByName, commits));
    return commits;
  }

  static void addCommit(
      final Map<String, Object> row,
      final Map<String, Commit> commitsByHash,
      final Map<String, Branch> branchesByName,
      final List<Commit> commits) {
    final Map<?, ?> properties = (Map<?, ?>) row.get("properties");
    final Commit commit =
        commitsByHash.computeIfAbsent((String) properties.get("hash"), Commit::new);
    commit.setBranch(branchesByName.computeIfAbsent((String) row.get("branch"), Branch::new));
    commit.setAuthorDate(toInstant(properties.get("authorDate")));
    commit.setCommitDate(toInstant(properties.get("commitDate")));
    commit.setHasAccumulatedMetrics(Boolean.TRUE.equals(properties.get("hasAccumulatedMetrics")));
    final Map<String, Double> metrics = new HashMap<>();
    properties.forEach(
        (key, value) -> {
          if (key instanceof String name
              && name.startsWith(METRIC_PREFIX)
              && value instanceof Number number) {
            metrics.put(name.substring(METRIC_PREFIX.length()), number.doubleValue());
          }
        });
    commit.setMetrics(metrics);
    final Object parentValues = row.get("parents");
    final Iterable<?> parents =
        parentValues instanceof Object[] array ? Arrays.asList(array) : (Iterable<?>) parentValues;
    for (final Object value : parents) {
      final Map<?, ?> parentProperties = (Map<?, ?>) value;
      final Commit parent =
          commitsByHash.computeIfAbsent((String) parentProperties.get("hash"), Commit::new);
      if (parentProperties.get("branch") instanceof String branch) {
        parent.setBranch(branchesByName.computeIfAbsent(branch, Branch::new));
      }
      if (Boolean.TRUE.equals(parentProperties.get("firstParent"))) {
        commit.setFirstParentCommit(parent);
      } else {
        commit.addMiscParentCommit(parent);
      }
    }
    commits.add(commit);
  }

  private static Instant toInstant(final Object value) {
    return value instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : null;
  }
}
