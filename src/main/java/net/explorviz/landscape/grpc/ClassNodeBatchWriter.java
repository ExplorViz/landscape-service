package net.explorviz.landscape.grpc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.proto.ClassData;
import org.neo4j.ogm.session.Session;

/**
 * Creates {@code Clazz} nodes for a batch of files in exactly two round-trips regardless of nesting
 * depth.
 *
 * <ol>
 *   <li>CREATE all {@code Clazz} nodes for the entire batch (no MATCH), collecting every node id.
 *   <li>Wire every {@code CONTAINS} relationship in a single UNWIND (both FileRevision→Clazz and
 *       Clazz→Clazz), using the ids returned from step 1.
 * </ol>
 *
 * <p>The previous depth-level loop required one round-trip per nesting level (O(maxDepth + 1)).
 * Deep inner classes — {@code A { B { C } } } — needed 3 queries; now always 2.
 */
@ApplicationScoped
public class ClassNodeBatchWriter {

  @Inject FileDataInsertProperties fileDataInsertProperties;

  private static final String CREATE_CLAZZ_NODES =
      """
      UNWIND $rows AS row
      CREATE (cl:Clazz)
      SET cl = row.props
      RETURN row.batchKey AS batchKey, id(cl) AS clazzId
      """;

  /**
   * Both lookups are label-free id seeks (O(1) each) so this query scales with batch size, not with
   * graph size.
   */
  private static final String WIRE_CLAZZ_RELATIONSHIPS =
      """
      UNWIND $rows AS row
      MATCH (parent) WHERE id(parent) = row.parentId
      MATCH (cl)     WHERE id(cl)     = row.clazzId
      CREATE (parent)-[:CONTAINS]->(cl)
      """;

  /**
   * Creates all {@code Clazz} nodes in {@code allPending} and wires their {@code CONTAINS}
   * relationships in exactly two UNWIND passes. Returns a map from each class's batch key to its
   * Neo4j node id.
   */
  Map<String, Long> createClassesByDepth(
      final Session session, final List<PendingClass> allPending) {
    if (allPending.isEmpty()) {
      return new LinkedHashMap<>();
    }

    final Map<String, Long> clazzIds = createAllClazzNodes(session, allPending);
    wireAllRelationships(session, allPending, clazzIds);
    return clazzIds;
  }

  private Map<String, Long> createAllClazzNodes(
      final Session session, final List<PendingClass> allPending) {
    final List<Map<String, Object>> rows = new ArrayList<>(allPending.size());
    for (final PendingClass pc : allPending) {
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("batchKey", pc.batchKey());
      row.put("props", buildClazzProps(pc.data()));
      rows.add(row);
    }

    final Map<String, Long> clazzIds = new LinkedHashMap<>(allPending.size() * 2);
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session
          .query(CREATE_CLAZZ_NODES, Map.of("rows", chunk))
          .queryResults()
          .forEach(r -> clazzIds.put((String) r.get("batchKey"), (Long) r.get("clazzId")));
    }
    return clazzIds;
  }

  private void wireAllRelationships(
      final Session session,
      final List<PendingClass> allPending,
      final Map<String, Long> clazzIds) {
    final List<Map<String, Object>> rows = new ArrayList<>(allPending.size());
    for (final PendingClass pc : allPending) {
      final long parentId =
          pc.depth() == 0 ? pc.parentFileRevId() : clazzIds.get(pc.parentBatchKey());
      final Map<String, Object> row = new LinkedHashMap<>();
      row.put("parentId", parentId);
      row.put("clazzId", clazzIds.get(pc.batchKey()));
      rows.add(row);
    }

    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session.query(WIRE_CLAZZ_RELATIONSHIPS, Map.of("rows", chunk));
    }
  }

  /**
   * Recursively flattens the class tree rooted at {@code classes} into {@code acc}, assigning each
   * class a unique batch key and recording its parent reference.
   */
  static void collectClasses(
      final List<ClassData> classes,
      final String parentKey,
      final String parentBatchKey,
      final Long parentFileRevId,
      final int depth,
      final List<PendingClass> acc) {
    for (int i = 0; i < classes.size(); i++) {
      final ClassData cd = classes.get(i);
      final String batchKey = parentKey + "/cls/" + depth + "/" + i + "/" + cd.getName();
      acc.add(new PendingClass(batchKey, parentBatchKey, parentFileRevId, depth, cd));
      if (!cd.getInnerClassesList().isEmpty()) {
        collectClasses(cd.getInnerClassesList(), batchKey, batchKey, null, depth + 1, acc);
      }
    }
  }

  private static Map<String, Object> buildClazzProps(final ClassData cd) {
    final Map<String, Object> props = new LinkedHashMap<>();
    props.put("name", cd.getName());
    props.put("type", cd.getType().name());
    props.put("modifiers", cd.getModifiersList());
    props.put("superclassFqns", cd.getSuperclassesList());
    props.put("implementedInterfaces", cd.getImplementedInterfacesList());
    props.put("annotations", cd.getAnnotationsList());
    props.put("enumValues", cd.getEnumValuesList());
    cd.getMetricsMap().forEach((k, v) -> props.put("metrics." + k, v));
    return props;
  }
}
