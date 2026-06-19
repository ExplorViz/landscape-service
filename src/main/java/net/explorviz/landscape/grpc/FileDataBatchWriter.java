package net.explorviz.landscape.grpc;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.proto.FileData;
import net.explorviz.landscape.repository.FileRevisionBatchResolver;
import org.neo4j.ogm.session.Session;

/**
 * Persists a batch of {@link FileData} messages in a single Neo4j transaction using explicit {@code
 * UNWIND} Cypher queries — one query per node type — rather than relying on OGM's per-entity
 * cascade save.
 *
 * <p>For a batch of N files, this produces O(max_class_depth + 6) bolt round-trips regardless of N,
 * compared with the OGM approach's O(12 × N) round-trips. Orchestration is split across {@link
 * ClassNodeBatchWriter} and {@link ChildNodeBatchWriter} to keep each class focused.
 *
 * <p>Large row lists are split into sub-batches of at most {@link
 * FileDataInsertProperties#getChunkSize()} rows before being sent to Neo4j. This bounds the Bolt
 * parameter payload per call and keeps Neo4j's query planning cost O(chunk) rather than O(N).
 */
@ApplicationScoped
public class FileDataBatchWriter {

  @Inject ClassNodeBatchWriter classNodeWriter;
  @Inject ChildNodeBatchWriter childNodeWriter;
  @Inject FileRevisionBatchResolver fileRevisionResolver;

  /**
   * Persists all files in {@code files} within the already-open {@code session}. The caller is
   * responsible for managing the surrounding transaction.
   */
  public void persistBatch(final Session session, final List<FileData> files) {
    if (files.isEmpty()) {
      return;
    }

    final long batchStart = System.nanoTime();
    long stepStart = batchStart;

    final Map<String, Long> fileRevIds = fileRevisionResolver.lookupFileRevisions(session, files);
    final long lookupFileRevisionsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    fileRevisionResolver.validateAllFilesFound(files, fileRevIds);
    final long validateAllFilesFoundMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    fileRevisionResolver.updateFileRevisions(session, files, fileRevIds);
    final long updateFileRevisionsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final List<PendingClass> allPending = collectAllClasses(files, fileRevIds);
    final long collectAllClassesMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final Map<String, Long> clazzIds = classNodeWriter.createClassesByDepth(session, allPending);
    final long createClassesByDepthMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    childNodeWriter.createFields(session, allPending, clazzIds);
    final long createFieldsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final Map<String, Long> functionIds = new LinkedHashMap<>();
    childNodeWriter.createClassFunctions(session, allPending, clazzIds, functionIds);
    final long createClassFunctionsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    childNodeWriter.createFileRevFunctions(session, files, fileRevIds, functionIds);
    final long createFileRevFunctionsMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    childNodeWriter.createAllParameters(session, allPending, files, functionIds);
    final long createAllParametersMs = elapsedMillis(stepStart);

    Log.infof(
        "persistBatch(%d files): lookupFileRevisions=%dms, validateAllFilesFound=%dms, "
            + "updateFileRevisions=%dms, collectAllClasses=%dms, createClassesByDepth=%dms, "
            + "createFields=%dms, createClassFunctions=%dms, createFileRevFunctions=%dms, "
            + "createAllParameters=%dms, total=%dms",
        files.size(),
        lookupFileRevisionsMs,
        validateAllFilesFoundMs,
        updateFileRevisionsMs,
        collectAllClassesMs,
        createClassesByDepthMs,
        createFieldsMs,
        createClassFunctionsMs,
        createFileRevFunctionsMs,
        createAllParametersMs,
        elapsedMillis(batchStart));
  }

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

  public static String makeBatchKey(final FileData f) {
    return f.getRepositoryName() + "/" + f.getFilePath() + "/" + f.getFileHash();
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * Splits {@code list} into consecutive sub-lists of at most {@code size} elements. The last
   * sub-list may be smaller. Returns a view; callers must not mutate the source list concurrently.
   */
  public static <T> List<List<T>> partition(final List<T> list, final int size) {
    final List<List<T>> chunks = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      chunks.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return chunks;
  }
}
