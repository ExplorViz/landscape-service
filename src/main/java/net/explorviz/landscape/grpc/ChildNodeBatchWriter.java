package net.explorviz.landscape.grpc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.proto.FieldData;
import net.explorviz.landscape.proto.FileData;
import net.explorviz.landscape.proto.FunctionData;
import net.explorviz.landscape.proto.ParameterData;
import org.neo4j.ogm.session.Session;

/**
 * Creates {@code Field}, {@code Function}, and {@code Parameter} nodes for a batch of files using
 * UNWIND Cypher — one query per node type.
 */
@ApplicationScoped
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public class ChildNodeBatchWriter {

  @Inject FileDataInsertProperties fileDataInsertProperties;

  private static final String CREATE_FIELDS =
      """
      UNWIND $rows AS row
      MATCH (parent) WHERE id(parent) = row.parentId
      CREATE (parent)-[:CONTAINS]->(fi:Field {name: row.name, type: row.type})
      SET fi.modifiers = row.modifiers
      """;

  private static final String CREATE_FUNCTIONS =
      """
      UNWIND $rows AS row
      MATCH (parent) WHERE id(parent) = row.parentId
      CREATE (parent)-[:CONTAINS]->(func:Function {name: row.name})
      SET func += row.props
      RETURN row.batchKey AS batchKey, id(func) AS functionId
      """;

  private static final String CREATE_PARAMETERS =
      """
      UNWIND $rows AS row
      MATCH (parent) WHERE id(parent) = row.parentId
      CREATE (parent)-[:CONTAINS]->(p:Parameter {name: row.name, type: row.type})
      SET p.modifiers = row.modifiers
      """;

  // ── Fields ────────────────────────────────────────────────────────────────

  void createFields(
      final Session session,
      final List<PendingClass> allPending,
      final Map<String, Long> clazzIds) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final PendingClass pc : allPending) {
      final long parentId = clazzIds.get(pc.batchKey());
      for (final FieldData fd : pc.data().getFieldsList()) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("parentId", parentId);
        row.put("name", fd.getName());
        row.put("type", fd.getType());
        row.put("modifiers", fd.getModifiersList());
        rows.add(row);
      }
    }
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session.query(CREATE_FIELDS, Map.of("rows", chunk));
    }
  }

  // ── Functions ─────────────────────────────────────────────────────────────

  void createClassFunctions(
      final Session session,
      final List<PendingClass> allPending,
      final Map<String, Long> clazzIds,
      final Map<String, Long> functionIds) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final PendingClass pc : allPending) {
      final long parentId = clazzIds.get(pc.batchKey());
      for (int i = 0; i < pc.data().getFunctionsList().size(); i++) {
        final FunctionData fn = pc.data().getFunctionsList().get(i);
        rows.add(buildFuncRow(classFuncKey(pc.batchKey(), i, fn), parentId, fn));
      }
    }
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session
          .query(CREATE_FUNCTIONS, Map.of("rows", chunk))
          .queryResults()
          .forEach(
              row -> functionIds.put((String) row.get("batchKey"), (Long) row.get("functionId")));
    }
  }

  void createFileRevFunctions(
      final Session session,
      final List<FileData> files,
      final Map<String, Long> fileRevIds,
      final Map<String, Long> functionIds) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    for (final FileData file : files) {
      final long parentId = fileRevIds.get(FileDataBatchWriter.makeBatchKey(file));
      for (int i = 0; i < file.getFunctionsList().size(); i++) {
        final FunctionData fn = file.getFunctionsList().get(i);
        rows.add(
            buildFuncRow(
                fileRevFuncKey(FileDataBatchWriter.makeBatchKey(file), i, fn), parentId, fn));
      }
    }
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session
          .query(CREATE_FUNCTIONS, Map.of("rows", chunk))
          .queryResults()
          .forEach(
              row -> functionIds.put((String) row.get("batchKey"), (Long) row.get("functionId")));
    }
  }

  // ── Parameters ────────────────────────────────────────────────────────────

  void createAllParameters(
      final Session session,
      final List<PendingClass> allPending,
      final List<FileData> files,
      final Map<String, Long> functionIds) {
    final List<Map<String, Object>> rows = new ArrayList<>();
    collectClassFuncParamRows(allPending, functionIds, rows);
    collectFileRevFuncParamRows(files, functionIds, rows);
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session.query(CREATE_PARAMETERS, Map.of("rows", chunk));
    }
  }

  private void collectClassFuncParamRows(
      final List<PendingClass> allPending,
      final Map<String, Long> functionIds,
      final List<Map<String, Object>> rows) {
    for (final PendingClass pc : allPending) {
      for (int i = 0; i < pc.data().getFunctionsList().size(); i++) {
        final FunctionData fn = pc.data().getFunctionsList().get(i);
        final Long funcId = functionIds.get(classFuncKey(pc.batchKey(), i, fn));
        if (funcId != null) {
          for (final ParameterData pd : fn.getParametersList()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("parentId", funcId);
            row.put("name", pd.getName());
            row.put("type", pd.getType());
            row.put("modifiers", pd.getModifiersList());
            rows.add(row);
          }
        }
      }
    }
  }

  private void collectFileRevFuncParamRows(
      final List<FileData> files,
      final Map<String, Long> functionIds,
      final List<Map<String, Object>> rows) {
    for (final FileData file : files) {
      for (int i = 0; i < file.getFunctionsList().size(); i++) {
        final FunctionData fn = file.getFunctionsList().get(i);
        final Long funcId =
            functionIds.get(fileRevFuncKey(FileDataBatchWriter.makeBatchKey(file), i, fn));
        if (funcId != null) {
          for (final ParameterData pd : fn.getParametersList()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("parentId", funcId);
            row.put("name", pd.getName());
            row.put("type", pd.getType());
            row.put("modifiers", pd.getModifiersList());
            rows.add(row);
          }
        }
      }
    }
  }

  // ── Row / property builders ───────────────────────────────────────────────

  private static Map<String, Object> buildFuncRow(
      final String batchKey, final long parentId, final FunctionData fn) {
    final Map<String, Object> row = new LinkedHashMap<>();
    row.put("batchKey", batchKey);
    row.put("parentId", parentId);
    row.put("name", fn.getName());
    row.put("props", buildFunctionProps(fn));
    return row;
  }

  private static Map<String, Object> buildFunctionProps(final FunctionData fn) {
    final Map<String, Object> props = new LinkedHashMap<>();
    props.put("returnType", fn.getReturnType());
    props.put("constructor", fn.getIsConstructor());
    props.put("annotations", fn.getAnnotationsList());
    props.put("modifiers", fn.getModifiersList());
    props.put("outgoingMethodCalls", fn.getOutgoingMethodCallsList());
    props.put("startLine", fn.getStartLine());
    props.put("endLine", fn.getEndLine());
    fn.getMetricsMap().forEach((k, v) -> props.put("metrics." + k, v));
    return props;
  }

  // ── Key helpers ───────────────────────────────────────────────────────────

  static String classFuncKey(final String clazzKey, final int idx, final FunctionData fn) {
    return clazzKey + "/fn/" + idx + "/" + fn.getName();
  }

  static String fileRevFuncKey(final String fileKey, final int idx, final FunctionData fn) {
    return fileKey + "/filefn/" + idx + "/" + fn.getName();
  }
}
