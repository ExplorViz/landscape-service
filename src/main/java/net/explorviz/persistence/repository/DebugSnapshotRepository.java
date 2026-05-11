package net.explorviz.persistence.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import net.explorviz.persistence.ogm.DebugSnapshot;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
public class DebugSnapshotRepository {

  public List<DebugSnapshot> findDebugSnapshotsForDebugRun(
      final Session session, final String debugRunId) {
    return Lists.newArrayList(
        session.query(
            DebugSnapshot.class,
            """
            MATCH (:DebugRun {id: $debugRunId})
                  -[:CONTAINS]->(s:DebugSnapshot)
            RETURN s
            """,
            Map.of("debugRunId", debugRunId)));
  }

}
