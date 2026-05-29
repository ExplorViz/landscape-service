package net.explorviz.landscape.grpc;

import io.grpc.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.explorviz.landscape.proto.FileData;
import org.neo4j.ogm.session.Session;

/**
 * Persists a batch of {@link FileData} messages in a single Neo4j transaction using explicit {@code
 * UNWIND} Cypher queries — one query per node type — rather than relying on OGM's per-entity
 * cascade save.
 *
 * <p>For a batch of N files, this produces O(max_class_depth + 6) bolt round-trips regardless of N,
 * compared with the OGM approach's O(12 × N) round-trips. Orchestration is split across {@link
 * ClassNodeBatchWriter} and {@link ChildNodeBatchWriter} to keep each class focused.
 */
@ApplicationScoped
public class FileDataBatchWriter {

  private static final String MATCH_FILE_REVISIONS =
      """
      UNWIND $rows AS row
      MATCH (f:FileRevision {repoName: row.repoName, filePath: row.filePath, hash: row.hash})
      RETURN row.batchKey AS batchKey, id(f) AS fileRevId
      """;

  private static final String UPDATE_FILE_REVISIONS =
      """
      UNWIND $rows AS row
      MATCH (f:FileRevision) WHERE id(f) = row.fileRevId
      SET f += row.props
      """;

  @Inject ClassNodeBatchWriter classNodeWriter;
  @Inject ChildNodeBatchWriter childNodeWriter;

  /**
   * Persists all files in {@code files} within the already-open {@code session}. The caller is
   * responsible for managing the surrounding transaction.
   */
  public void persistBatch(final Session session, final List<FileData> files) {
    if (files.isEmpty()) {
      return;
    }

    final Map<String, Long> fileRevIds = lookupFileRevisions(session, files);
    validateAllFilesFound(files, fileRevIds);
    updateFileRevisions(session, files, fileRevIds);

    final List<PendingClass> allPending = collectAllClasses(files, fileRevIds);
    final Map<String, Long> clazzIds = classNodeWriter.createClassesByDepth(session, allPending);

    childNodeWriter.createFields(session, allPending, clazzIds);
    final Map<String, Long> functionIds = new LinkedHashMap<>();
    childNodeWriter.createClassFunctions(session, allPending, clazzIds, functionIds);
    childNodeWriter.createFileRevFunctions(session, files, fileRevIds, functionIds);
    childNodeWriter.createAllParameters(session, allPending, files, functionIds);
  }

  // ── FileRevision lookup and update ───────────────────────────────────────

  private Map<String, Long> lookupFileRevisions(final Session session, final List<FileData> files) {
    final List<Map<String, Object>> rows =
        files.stream()
            .map(
                f -> {
                  final Map<String, Object> row = new LinkedHashMap<>();
                  row.put("batchKey", makeBatchKey(f));
                  row.put("repoName", f.getRepositoryName());
                  row.put("filePath", f.getFilePath());
                  row.put("hash", f.getFileHash());
                  return row;
                })
            .collect(Collectors.toList());

    final Map<String, Long> result = new LinkedHashMap<>();
    session
        .query(MATCH_FILE_REVISIONS, Map.of("rows", rows))
        .queryResults()
        .forEach(row -> result.put((String) row.get("batchKey"), (Long) row.get("fileRevId")));
    return result;
  }

  private void validateAllFilesFound(
      final List<FileData> files, final Map<String, Long> fileRevIds) {
    for (final FileData file : files) {
      if (!fileRevIds.containsKey(makeBatchKey(file))) {
        throw Status.FAILED_PRECONDITION
            .withDescription(
                "No corresponding file was sent before in CommitData: " + file.getFilePath())
            .asRuntimeException();
      }
    }
  }

  private void updateFileRevisions(
      final Session session, final List<FileData> files, final Map<String, Long> fileRevIds) {
    final List<Map<String, Object>> rows =
        files.stream()
            .map(
                f -> {
                  final Map<String, Object> row = new LinkedHashMap<>();
                  row.put("fileRevId", fileRevIds.get(makeBatchKey(f)));
                  row.put("props", buildFileRevisionProps(f));
                  return row;
                })
            .collect(Collectors.toList());
    session.query(UPDATE_FILE_REVISIONS, Map.of("rows", rows));
  }

  // ── Class tree collection ─────────────────────────────────────────────────

  private List<PendingClass> collectAllClasses(
      final List<FileData> files, final Map<String, Long> fileRevIds) {
    final List<PendingClass> acc = new ArrayList<>();
    for (final FileData file : files) {
      final long fileRevId = fileRevIds.get(makeBatchKey(file));
      ClassNodeBatchWriter.collectClasses(
          file.getClassesList(), makeBatchKey(file), null, fileRevId, 0, acc);
    }
    return acc;
  }

  // ── Property builder ─────────────────────────────────────────────────────

  static Map<String, Object> buildFileRevisionProps(final FileData f) {
    final Map<String, Object> props = new LinkedHashMap<>();
    props.put("language", f.getLanguage().toString());
    if (f.hasPackageName()) {
      props.put("packageName", f.getPackageName());
    }
    props.put("importNames", f.getImportNamesList());
    props.put("lastEditor", f.getLastEditor());
    props.put("addedLines", f.getAddedLines());
    props.put("modifiedLines", f.getModifiedLines());
    props.put("deletedLines", f.getDeletedLines());
    props.put("hasFileData", true);
    f.getMetricsMap().forEach((k, v) -> props.put("metrics." + k, v));
    return props;
  }

  // ── Key utility ───────────────────────────────────────────────────────────

  static String makeBatchKey(final FileData f) {
    return f.getRepositoryName() + "/" + f.getFilePath() + "/" + f.getFileHash();
  }
}
