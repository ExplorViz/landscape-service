package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

public class TestUtils {

  private TestUtils() {}

  public static Map<String, Object> getNodeCountMap(Session session) {
    Result result =
        session.query(
            """
            RETURN
              COUNT {(:Landscape)} AS landscapes,
              COUNT {(:Repository)} AS repositories,
              COUNT {(:Branch)} AS branches,
              COUNT {(:Commit)} AS commits,
              COUNT {(:Tag)} AS tags,
              COUNT {(:Application)} AS applications,
              COUNT {(:Directory)} AS directories,
              COUNT {(:FileRevision)} AS files,
              COUNT {(:Clazz)} AS classes,
              COUNT {(:Field)} AS fields,
              COUNT {(:Function)} AS functions,
              COUNT {(:Parameter)} AS parameters,
              COUNT {(:Trace)} AS traces,
              COUNT {(:Span)} AS spans;
            """,
            Map.of());

    return result.queryResults().iterator().next();
  }

  public static void assertNodeCounts(Session session, Map<String, Long> expected) {
    Map<String, Object> actual = getNodeCountMap(session);
    for (NodeCountType type : NodeCountType.values()) {
      String key = type.key();

      long expectedValue = expected.getOrDefault(key, 0L);
      long actualValue = (Long) actual.getOrDefault(key, 0L);

      assertEquals(expectedValue, actualValue, "Mismatch for type: " + key);
    }
  }
}
