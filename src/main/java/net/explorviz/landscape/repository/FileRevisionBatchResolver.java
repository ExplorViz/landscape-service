package net.explorviz.landscape.repository;

import io.grpc.Status;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.explorviz.landscape.grpc.FileDataBatchWriter;
import net.explorviz.landscape.grpc.FileDataInsertProperties;
import net.explorviz.landscape.proto.FileData;
import org.neo4j.ogm.session.Session;

/** Resolves and updates {@code FileRevision} nodes for a batch of {@link FileData} messages. */
@ApplicationScoped
public class FileRevisionBatchResolver {

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

  @Inject FileRevisionIdCache fileRevisionIdCache;
  @Inject FileDataInsertProperties fileDataInsertProperties;

  public Map<String, Long> lookupFileRevisions(final Session session, final List<FileData> files) {
    final Map<String, Long> result = new LinkedHashMap<>();
    final List<FileData> missingFromCache = new ArrayList<>();

    for (final FileData file : files) {
      final String batchKey = FileDataBatchWriter.makeBatchKey(file);
      final String cacheKey = FileRevisionLookupKey.fromFileData(file).cacheKey();
      final Long cachedId = fileRevisionIdCache.get(cacheKey);
      if (cachedId != null) {
        result.put(batchKey, cachedId);
      } else {
        missingFromCache.add(file);
      }
    }

    if (!missingFromCache.isEmpty()) {
      lookupFileRevisionsFromDatabase(session, missingFromCache, result);
    }

    return result;
  }

  public void validateAllFilesFound(
      final List<FileData> files, final Map<String, Long> fileRevIds) {
    for (final FileData file : files) {
      if (!fileRevIds.containsKey(FileDataBatchWriter.makeBatchKey(file))) {
        throw Status.FAILED_PRECONDITION
            .withDescription(
                "No corresponding file was sent before in CommitData: " + file.getFilePath())
            .asRuntimeException();
      }
    }
  }

  public void updateFileRevisions(
      final Session session, final List<FileData> files, final Map<String, Long> fileRevIds) {
    final List<Map<String, Object>> rows =
        files.stream()
            .map(
                f -> {
                  final Map<String, Object> row = new LinkedHashMap<>();
                  row.put("fileRevId", fileRevIds.get(FileDataBatchWriter.makeBatchKey(f)));
                  row.put("props", buildFileRevisionProps(f));
                  return row;
                })
            .collect(Collectors.toList());
    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session.query(UPDATE_FILE_REVISIONS, Map.of("rows", chunk));
    }
  }

  private void lookupFileRevisionsFromDatabase(
      final Session session, final List<FileData> files, final Map<String, Long> result) {
    final Map<String, FileData> filesByBatchKey = new LinkedHashMap<>();
    for (final FileData file : files) {
      filesByBatchKey.put(FileDataBatchWriter.makeBatchKey(file), file);
    }

    final List<Map<String, Object>> rows =
        files.stream()
            .map(
                f -> {
                  final Map<String, Object> row = new LinkedHashMap<>();
                  row.put("batchKey", FileDataBatchWriter.makeBatchKey(f));
                  row.put("repoName", f.getRepositoryName());
                  row.put("filePath", f.getFilePath());
                  row.put("hash", f.getFileHash());
                  return row;
                })
            .collect(Collectors.toList());

    for (final List<Map<String, Object>> chunk :
        FileDataBatchWriter.partition(rows, fileDataInsertProperties.getChunkSize())) {
      session
          .query(MATCH_FILE_REVISIONS, Map.of("rows", chunk))
          .queryResults()
          .forEach(
              row -> {
                final String batchKey = (String) row.get("batchKey");
                final Long fileRevId = (Long) row.get("fileRevId");
                result.put(batchKey, fileRevId);
                final FileData file = filesByBatchKey.get(batchKey);
                if (file != null) {
                  fileRevisionIdCache.put(
                      FileRevisionLookupKey.fromFileData(file).cacheKey(), fileRevId);
                }
              });
    }
  }

  private static Map<String, Object> buildFileRevisionProps(final FileData f) {
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
}
