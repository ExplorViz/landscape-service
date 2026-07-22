package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Stream;
import net.explorviz.landscape.proto.FileIdentifier;
import net.explorviz.landscape.repository.FileRevisionRepository.CommitFileLinkType;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
class CommitFileStructureBatchWriter {

  private static final String LINK_FILE_REVISIONS_BASE =
      """
      MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
        -[:CONTAINS]->(commit:Commit {hash: $commitHash})
      WITH commit
      UNWIND $files AS file
      MATCH (parent:Directory) WHERE id(parent) = file.parentDirId
      MERGE (parent)-[:CONTAINS]->(f:FileRevision {hash: file.hash, name: file.fileName})
      ON CREATE SET f.hasFileData = false, f.filePath = file.filePath, f.repoName = $repoName
      MERGE (commit)-[:CONTAINS]->(f)
      """;

  private static final String LINK_FILE_REVISIONS_RETURN =
      """
      RETURN file.filePath AS filePath, file.hash AS hash, id(f) AS fileRevId
      """;

  @Inject DirectoryRepository directoryRepository;
  @Inject FileRevisionIdCache fileRevisionIdCache;
  @Inject CommitFileRevisionCache commitFileRevisionCache;

  void createAndLinkFileStructureBatch(
      final Session session,
      final CommitFilePersistenceContext context,
      final List<FileIdentifier> fileIdentifiers,
      final CommitFileLinkType linkType) {
    if (fileIdentifiers.isEmpty()) {
      return;
    }

    final long batchStart = System.nanoTime();
    long stepStart = batchStart;

    final Map<String, String[]> uniqueDirPaths =
        collectUniqueDirectoryPaths(fileIdentifiers, context.repoName());
    directoryRepository.batchMergeDirectoryPaths(
        session,
        context.rootDirectoryId(),
        context.repoName(),
        uniqueDirPaths,
        context.directoryLeafCache());
    final long mergeDirectoriesMs = elapsedMillis(stepStart);

    stepStart = System.nanoTime();
    final List<Map<String, Object>> filesToLink =
        buildFilesToLinkPayload(fileIdentifiers, context.repoName(), context.directoryLeafCache());
    linkFileRevisionsBatch(
        session,
        Map.of(
            "tokenId", context.landscapeTokenId(),
            "repoName", context.repoName(),
            "commitHash", context.commitHash(),
            "files", filesToLink),
        linkType,
        context);
    final long linkFileRevisionsMs = elapsedMillis(stepStart);

    populateCommitFileRevisionCache(context, fileIdentifiers);

    Log.infof(
        "linkCommitFileBatch(%d files, %s): mergeDirectories=%dms, linkFileRevisions=%dms, "
            + "total=%dms",
        fileIdentifiers.size(),
        linkType,
        mergeDirectoriesMs,
        linkFileRevisionsMs,
        elapsedMillis(batchStart));
  }

  private Map<String, String[]> collectUniqueDirectoryPaths(
      final List<FileIdentifier> fileIdentifiers, final String repoName) {
    final Map<String, String[]> uniqueDirPaths = new HashMap<>();
    for (final FileIdentifier fileIdentifier : fileIdentifiers) {
      final String[] pathSegments = fileIdentifier.getFilePath().split("/");
      final String[] directorySegments = buildDirectorySegments(repoName, pathSegments);
      for (int depth = 1; depth <= directorySegments.length; depth++) {
        final String key = String.join("/", Arrays.copyOfRange(directorySegments, 0, depth));
        uniqueDirPaths.putIfAbsent(key, Arrays.copyOfRange(directorySegments, 0, depth));
      }
    }
    return uniqueDirPaths;
  }

  private List<Map<String, Object>> buildFilesToLinkPayload(
      final List<FileIdentifier> fileIdentifiers,
      final String repoName,
      final Map<String, Long> directoryLeafCache) {
    final List<Map<String, Object>> filesToLink = new ArrayList<>(fileIdentifiers.size());
    for (final FileIdentifier fileIdentifier : fileIdentifiers) {
      final String[] pathSegments = fileIdentifier.getFilePath().split("/");
      final String[] directorySegments = buildDirectorySegments(repoName, pathSegments);
      final String directoryKey = String.join("/", directorySegments);
      final Long parentDirId = directoryLeafCache.get(directoryKey);
      if (parentDirId == null) {
        throw new NoSuchElementException(
            "Parent directory not found in cache for path: " + fileIdentifier.getFilePath());
      }
      filesToLink.add(
          Map.of(
              "parentDirId",
              parentDirId,
              "fileName",
              pathSegments[pathSegments.length - 1],
              "hash",
              fileIdentifier.getFileHash(),
              "filePath",
              fileIdentifier.getFilePath()));
    }
    return filesToLink;
  }

  private void linkFileRevisionsBatch(
      final Session session,
      final Map<String, Object> params,
      final CommitFileLinkType linkType,
      final CommitFilePersistenceContext context) {
    final String typedCommitLinkClause =
        switch (linkType) {
          case ADDED -> "MERGE (commit)-[:ADDED]->(f)";
          case MODIFIED -> "MERGE (commit)-[:MODIFIED]->(f)";
          case CONTAINS -> "";
        };
    session
        .query(
            LINK_FILE_REVISIONS_BASE + typedCommitLinkClause + LINK_FILE_REVISIONS_RETURN, params)
        .queryResults()
        .forEach(
            row -> {
              final String filePath = (String) row.get("filePath");
              final String hash = (String) row.get("hash");
              final Long fileRevId = (Long) row.get("fileRevId");
              final String lookupKey =
                  new FileRevisionLookupKey(
                          context.landscapeTokenId(), context.repoName(), filePath, hash)
                      .cacheKey();
              fileRevisionIdCache.put(lookupKey, fileRevId);
            });
  }

  private void populateCommitFileRevisionCache(
      final CommitFilePersistenceContext context, final List<FileIdentifier> fileIdentifiers) {
    final Map<String, CommitFileRevisionCache.FileRevEntry> entries =
        new HashMap<>(fileIdentifiers.size());
    for (final FileIdentifier fi : fileIdentifiers) {
      final String lookupKey =
          new FileRevisionLookupKey(
                  context.landscapeTokenId(),
                  context.repoName(),
                  fi.getFilePath(),
                  fi.getFileHash())
              .cacheKey();
      final Long fileRevId = fileRevisionIdCache.get(lookupKey);
      if (fileRevId != null) {
        entries.put(
            fi.getFilePath(),
            new CommitFileRevisionCache.FileRevEntry(fi.getFileHash(), fileRevId));
      }
    }
    commitFileRevisionCache.putAll(context.commitInternalId(), entries);
  }

  private static long elapsedMillis(final long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  private String[] buildDirectorySegments(final String repoName, final String[] pathSegments) {
    if (pathSegments.length <= 1) {
      return new String[] {repoName};
    }
    final String[] directorySegments = Arrays.copyOfRange(pathSegments, 0, pathSegments.length - 1);
    return Stream.concat(Stream.of(repoName), Arrays.stream(directorySegments))
        .toArray(String[]::new);
  }
}
