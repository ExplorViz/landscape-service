package net.explorviz.persistence.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import net.explorviz.persistence.ogm.Variable;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class VariableRepository {

  public List<Variable> findVariablesForDebugSnapshot(
      final Session session, final String debugSnapshotId) {
    return Lists.newArrayList(
        session.query(
            Variable.class,
            """
            MATCH (:DebugSnapshot {id: $debugSnapshotId})
                  -[:CAPTURES]->(v:Variable)
            RETURN v
            """,
            Map.of("debugSnapshotId", debugSnapshotId)));
  }
}
