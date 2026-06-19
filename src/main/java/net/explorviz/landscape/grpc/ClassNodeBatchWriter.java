package net.explorviz.landscape.grpc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.proto.ClassData;
import org.neo4j.ogm.session.Session;

/** Creates {@code Clazz} nodes for a batch of files, processing one class-depth level per query. */
@ApplicationScoped
public class ClassNodeBatchWriter {

  @Inject FileDataInsertProperties fileDataInsertProperties;

  private static final String CREATE_CLASSES_ON_FILE_REVISION =
      """
      UNWIND $rows AS row
      MATCH (parent:FileRevision) WHERE id(parent) = row.parentId
      CREATE (parent)-[:CONTAINS]->(cl:Clazz {name: row.name})
      SET cl += row.props
      RETURN row.batchKey AS batchKey, id(cl) AS clazzId
      """;

  private static final String CREATE_CLASSES_ON_CLAZZ =
      """
      UNWIND $rows AS row
      MATCH (parent:Clazz) WHERE id(parent) = row.parentId
      CREATE (parent)-[:CONTAINS]->(cl:Clazz {name: row.name})
      SET cl += row.props
      RETURN row.batchKey AS batchKey, id(cl) AS clazzId
      """;

  /**
   * Creates all {@code Clazz} nodes in {@code allPending}, processing each depth level in a single
   * UNWIND query. Returns a map from each class's batch key to its Neo4j node ID.
   */
  Map<String, Long> createClassesByDepth(
      final Session session, final List<PendingClass> allPending) {
    final int maxDepth = allPending.stream().mapToInt(PendingClass::depth).max().orElse(-1);
    final Map<String, Long> clazzIds = new LinkedHashMap<>();

    for (int depth = 0; depth <= maxDepth; depth++) {
      final List<Map<String, Object>> rows = buildRowsForDepth(allPending, depth, clazzIds);
      if (!rows.isEmpty()) {
        createClassesAtDepth(session, depth, rows, clazzIds);
      }
    }
    return clazzIds;
  }

  private static List<Map<String, Object>> buildRowsForDepth(
      final List<PendingClass> allPending, final int depth, final Map<String, Long> clazzIds) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final PendingClass pc : allPending) {
      if (pc.depth() == depth) {
        rows.add(buildClassRow(pc, depth, clazzIds));
      }
    }
    return rows;
  }

  private static Map<String, Object> buildClassRow(
      final PendingClass pc, final int depth, final Map<String, Long> clazzIds) {
    final long parentId = depth == 0 ? pc.parentFileRevId() : clazzIds.get(pc.parentBatchKey());
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("batchKey", pc.batchKey());
    row.put("parentId", parentId);
    row.put("name", pc.data().getName());
    row.put("props", buildClazzProps(pc.data()));
    return row;
  }

  private void createClassesAtDepth(
      final Session session,
      final int depth,
      final List<Map<String, Object>> rows,
      final Map<String, Long> clazzIds) {
    final String query = depth == 0 ? CREATE_CLASSES_ON_FILE_REVISION : CREATE_CLASSES_ON_CLAZZ;
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session
          .query(query, Map.of("rows", chunk))
          .queryResults()
          .forEach(row -> clazzIds.put((String) row.get("batchKey"), (Long) row.get("clazzId")));
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
