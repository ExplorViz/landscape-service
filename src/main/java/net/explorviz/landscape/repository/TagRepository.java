package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.landscape.ogm.Tag;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
public class TagRepository {

  @Inject SessionFactory sessionFactory;

  public Optional<Tag> findTagByNameAndRepositoryNameAndLandscapeToken(
      final Session session,
      final String tagName,
      final String repoName,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            Tag.class,
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(t:Tag {name: $name})
            RETURN t;
            """,
            Map.of("name", tagName, "repoName", repoName, "landscapeToken", landscapeToken)));
  }

  /** Returns git tag names grouped by commit hash for all tagged commits in the repository. */
  public Map<String, List<String>> findTagNamesByCommitHashForRepository(
      final Session session, final String landscapeToken, final String repositoryName) {
    final Map<String, List<String>> tagsByCommitHash = new HashMap<>();

    session
        .query(
            """
            MATCH (:Landscape {tokenId: $landscapeToken})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:CONTAINS]->(c:Commit)-[:IS_TAGGED_WITH]->(t:Tag)
            WHERE t.name IS NOT NULL
            RETURN c.hash AS commitHash, collect(DISTINCT t.name) AS tagNames
            """,
            Map.of(
                "landscapeToken", landscapeToken,
                "repoName", repositoryName))
        .queryResults()
        .forEach(
            row -> {
              final String commitHash = (String) row.get("commitHash");
              final List<String> tagNames = castTagNames(row.get("tagNames"));
              if (commitHash != null && !tagNames.isEmpty()) {
                tagsByCommitHash.put(commitHash, tagNames.stream().sorted().toList());
              }
            });

    return tagsByCommitHash;
  }

  private static List<String> castTagNames(final Object tagNames) {
    if (tagNames == null) {
      return List.of();
    }
    if (tagNames instanceof String string) {
      return List.of(string);
    }
    if (tagNames.getClass().isArray()) {
      return castTagNamesFromArray(tagNames);
    }
    if (tagNames instanceof Iterable<?> values) {
      return castTagNamesFromIterable(values);
    }
    return List.of();
  }

  private static List<String> castTagNamesFromIterable(final Iterable<?> values) {
    final List<String> result = new ArrayList<>();
    for (final Object value : values) {
      addStringValue(result, value);
    }
    return result;
  }

  private static List<String> castTagNamesFromArray(final Object values) {
    final int length = Array.getLength(values);
    final List<String> result = new ArrayList<>(length);
    for (int index = 0; index < length; index++) {
      addStringValue(result, Array.get(values, index));
    }
    return result;
  }

  private static void addStringValue(final List<String> result, final Object value) {
    if (value instanceof String string) {
      result.add(string);
    }
  }
}
