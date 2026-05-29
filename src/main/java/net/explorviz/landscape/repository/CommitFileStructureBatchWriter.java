package net.explorviz.landscape.repository;

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

  @Inject DirectoryRepository directoryRepository;

  void createAndLinkFileStructureBatch(
      final Session session,
      final List<FileIdentifier> fileIdentifiers,
      final String repoName,
      final String landscapeTokenId,
      final String commitHash,
      final CommitFileLinkType linkType,
      final Long rootDirectoryId,
      final Map<String, Long> directoryLeafCache) {
    if (fileIdentifiers.isEmpty()) {
      return;
    }

    final Map<String, String[]> uniqueDirPaths =
        collectUniqueDirectoryPaths(fileIdentifiers, repoName);
    directoryRepository.batchMergeDirectoryPaths(
        session, rootDirectoryId, repoName, uniqueDirPaths, directoryLeafCache);

    final List<Map<String, Object>> filesToLink =
        buildFilesToLinkPayload(fileIdentifiers, repoName, directoryLeafCache);
    linkFileRevisionsBatch(
        session,
        Map.of(
            "tokenId", landscapeTokenId,
            "repoName", repoName,
            "commitHash", commitHash,
            "files", filesToLink),
        linkType);
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
      final Session session, final Map<String, Object> params, final CommitFileLinkType linkType) {
    final String commitLinkClause =
        switch (linkType) {
          case ADDED -> "MERGE (commit)-[:ADDED]->(f)";
          case MODIFIED -> "MERGE (commit)-[:MODIFIED]->(f)";
          case CONTAINS -> "";
        };
    session.query(LINK_FILE_REVISIONS_BASE + commitLinkClause, params);
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
