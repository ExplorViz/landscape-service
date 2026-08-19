package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.proto.FileIdentifier;
import org.jboss.logging.Logger;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class FileRevisionRepository {

  private static final String FIND_FILE_DETAILED_CONTEXT =
      """
      MATCH (f:FileRevision) WHERE id(f) = $fileRevId
      MATCH (l:Landscape {tokenId: $landscapeToken})-[:CONTAINS]->(repo:Repository)
      MATCH (repo)-[:CONTAINS]->(c:Commit)-[:CONTAINS]->(f)
      MATCH (l)-[:CONTAINS]->(:Application)-[:HAS_ROOT]->(appRoot:Directory)
      MATCH p = (appRoot)-[:CONTAINS]->*(f)
      WITH f, repo, c, [node IN nodes(p)[1..] | node.name] AS nodeNames
      RETURN
        id(f) AS fileRevisionId,
        repo.remoteUrl AS remoteUrl,
        repo.name AS repositoryName,
        c.hash AS commitHash,
        apoc.text.join(nodeNames, "/") AS fqn
      ORDER BY c.commitDate DESC
      LIMIT 1
      """;
  private static final Logger LOGGER = Logger.getLogger(FileRevisionRepository.class);
  public static final int COMMIT_FILE_BATCH_SIZE = 2000;

  public enum CommitFileLinkType {
    CONTAINS,
    ADDED,
    MODIFIED
  }

  @Inject DirectoryRepository directoryRepository;
  @Inject CommitFileStructureBatchWriter commitFileStructureBatchWriter;
  @Inject UnchangedCommitFileCopier unchangedCommitFileCopier;
  @Inject CommitRepository commitRepository;

  public CommitFilePersistenceContext createFilePersistenceContext(
      final Session session,
      final String landscapeTokenId,
      final String repoName,
      final String commitHash,
      final long commitInternalId) {
    return CommitFilePersistenceContext.create(
        session, directoryRepository, landscapeTokenId, repoName, commitHash, commitInternalId);
  }

  public record CopyUnchangedFilesFromParentRequest(
      String landscapeTokenId,
      String repoName,
      String parentCommitHash,
      long childCommitInternalId,
      Set<String> addedPaths,
      Set<String> modifiedPaths,
      Set<String> deletedPaths,
      boolean requirePersistedParent) {}

  /**
   * Links every parent file revision not listed as added, modified, or deleted to the child commit.
   *
   * @return number of unchanged file revisions linked from the parent commit
   */
  public int copyUnchangedFilesFromParentCommit(
      final Session session, final CopyUnchangedFilesFromParentRequest request) {
    final Optional<Long> parentCommitInternalId =
        commitRepository.findCommitInternalIdInRepositoryWithRetry(
            session,
            request.parentCommitHash(),
            request.landscapeTokenId(),
            request.repoName(),
            request.requirePersistedParent());
    if (parentCommitInternalId.isEmpty()) {
      if (request.requirePersistedParent()) {
        Log.warnf(
            "No analyzed parent commit available for %s in repository '%s'; skipping"
                + " unchanged-file inheritance for child %d",
            request.parentCommitHash(), request.repoName(), request.childCommitInternalId());
      } else {
        Log.debugf(
            "Parent commit %s not found in repository '%s'; skipping unchanged-file copy to child"
                + " %d",
            request.parentCommitHash(), request.repoName(), request.childCommitInternalId());
      }
      return 0;
    }

    return unchangedCommitFileCopier.copyFromParent(
        session,
        new UnchangedCommitFileCopier.CopyUnchangedFilesFromParentRequest(
            request.landscapeTokenId(),
            request.repoName(),
            request.parentCommitHash(),
            parentCommitInternalId.get(),
            request.childCommitInternalId(),
            request.addedPaths(),
            request.modifiedPaths(),
            request.deletedPaths(),
            request.requirePersistedParent()));
  }

  /**
   * Verifies that a just-persisted commit's in-memory file cache exactly mirrors its linked file
   * revisions in the graph and, if so, marks it as an O(1) copy source for its future children.
   * Should be called once a commit's own file-linking steps (added, modified, unchanged, copy from
   * parent, and stale/deleted unlinking) have all completed.
   */
  public void verifyAndCacheCommitCompleteness(final Session session, final long commitInternalId) {
    unchangedCommitFileCopier.verifyAndMarkComplete(session, commitInternalId);
  }

  public FileRevision createFileStructureFromStaticData(
      final Session session,
      final FileIdentifier fileIdentifier,
      final String repoName,
      final String landscapeTokenId,
      final Commit commit) {
    final CommitFilePersistenceContext context =
        CommitFilePersistenceContext.create(
            session,
            directoryRepository,
            landscapeTokenId,
            repoName,
            commit.getHash(),
            commit.getId());
    commitFileStructureBatchWriter.createAndLinkFileStructureBatch(
        session, context, List.of(fileIdentifier), CommitFileLinkType.CONTAINS);
    return getFileRevisionFromHashAndPath(
            session,
            fileIdentifier.getFileHash(),
            repoName,
            landscapeTokenId,
            fileIdentifier.getFilePath().split("/"))
        .orElseThrow();
  }

  public void persistCommitFilesInBatches(
      final Session session,
      final CommitFilePersistenceContext context,
      final List<FileIdentifier> fileIdentifiers,
      final CommitFileLinkType linkType) {
    if (fileIdentifiers.isEmpty()) {
      return;
    }

    if (fileIdentifiers.size() > COMMIT_FILE_BATCH_SIZE) {
      LOGGER.debugf(
          "Linking %d commit file stubs in batches of %d for repository '%s'",
          fileIdentifiers.size(), COMMIT_FILE_BATCH_SIZE, context.repoName());
    }

    for (int offset = 0; offset < fileIdentifiers.size(); offset += COMMIT_FILE_BATCH_SIZE) {
      final int end = Math.min(offset + COMMIT_FILE_BATCH_SIZE, fileIdentifiers.size());
      final List<FileIdentifier> batch = fileIdentifiers.subList(offset, end);
      persistCommitFileBatch(session, context, batch, linkType);

      if (end % (COMMIT_FILE_BATCH_SIZE * 5) == 0 || end == fileIdentifiers.size()) {
        LOGGER.debugf(
            "Linked %d of %d commit file stubs for repository '%s'",
            end, fileIdentifiers.size(), context.repoName());
      }
    }
  }

  public void persistCommitFileBatch(
      final Session session,
      final CommitFilePersistenceContext context,
      final List<FileIdentifier> fileIdentifiers,
      final CommitFileLinkType linkType) {
    if (fileIdentifiers.isEmpty()) {
      return;
    }
    commitFileStructureBatchWriter.createAndLinkFileStructureBatch(
        session, context, fileIdentifiers, linkType);
    session.clear();
  }

  public Optional<FileRevision> getFileRevisionFromHashAndPath(
      final Session session,
      final String fileHash,
      final String repoName,
      final String landscapeTokenId,
      final String[] pathSegments) {
    return Optional.ofNullable(
        session.queryForObject(
            FileRevision.class,
            """
                MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(root:Directory)
                MATCH p = (root)-[:CONTAINS]->*(file:FileRevision {hash: $fileHash})
                WHERE all(j in range(1, length(p)) WHERE nodes(p)[j].name=$pathSegments[j-1])
                  AND length(p)=size($pathSegments)
                RETURN file
                ORDER BY file.hasFileData ASC, id(file) DESC
                LIMIT 1;
            """,
            Map.of(
                "tokenId",
                landscapeTokenId,
                "repoName",
                repoName,
                "fileHash",
                fileHash,
                "pathSegments",
                pathSegments)));
  }

  /**
   * Retrieve all FileRevisions from static analysis along with their file paths for a given
   * application at a particular commit.
   *
   * @return A map of each file's path to the corresponding FileRevision object, separated by '/'.
   */
  public Map<String, FileRevision> findStaticFilesWithFqnForApplicationAndCommitAndLandscapeToken(
      final Session session,
      final String applicationName,
      final String commitHash,
      final String landscapeToken) {

    final Map<String, FileRevision> filePathToFileRevisionMap = new HashMap<>();

    final Result result =
        session.query(
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Application {name: $appName})
              -[:HAS_ROOT]->(appRoot:Directory)
            WHERE (l)
              -[:CONTAINS]->(:Repository)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS*0..]->(appRoot)
            MATCH p = (appRoot)-[:CONTAINS]->*(f:FileRevision)
            WHERE (:Commit {hash: $commitHash})-[:CONTAINS]->(f)
            WITH f, [node IN nodes(p)[1..] | node.name] AS nodeNames
            RETURN DISTINCT
              f AS file,
              apoc.text.join(nodeNames, "/") AS filePath;
            """,
            Map.of(
                "tokenId", landscapeToken, "appName", applicationName, "commitHash", commitHash));

    result
        .queryResults()
        .forEach(
            queryResult ->
                filePathToFileRevisionMap.put(
                    (String) queryResult.get("filePath"), (FileRevision) queryResult.get("file")));

    return filePathToFileRevisionMap;
  }

  public Map<String, FileRevision> findStaticFilesWithFqnForRepositoryAndCommitAndLandscapeToken(
      final Session session,
      final String repoName,
      final String commitHash,
      final String landscapeToken) {

    final Map<String, FileRevision> filePathToFileRevisionMap = new HashMap<>();

    final Result result =
        session.query(
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository {name: $repoName})
              -[:HAS_ROOT]->(repoRoot:Directory)
            MATCH p = (repoRoot)-[:CONTAINS*]->(f:FileRevision)
            WHERE (:Commit {hash: $commitHash})-[:CONTAINS]->(f)
            WITH f, [node IN nodes(p)[1..] | node.name] AS nodeNames
            RETURN DISTINCT
              f AS file,
              apoc.text.join(nodeNames, "/") AS filePath;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repoName, "commitHash", commitHash));

    result
        .queryResults()
        .forEach(
            queryResult ->
                filePathToFileRevisionMap.put(
                    (String) queryResult.get("filePath"), (FileRevision) queryResult.get("file")));

    return filePathToFileRevisionMap;
  }

  public Optional<FileDetailedContext> findFileDetailedContext(
      final Session session, final String landscapeToken, final Long fileRevisionId) {
    final Result result =
        session.query(
            FIND_FILE_DETAILED_CONTEXT,
            Map.of("landscapeToken", landscapeToken, "fileRevId", fileRevisionId));

    final Iterator<Map<String, Object>> resultIterator = result.queryResults().iterator();
    if (!resultIterator.hasNext()) {
      return Optional.empty();
    }

    final Map<String, Object> row = resultIterator.next();
    final Long loadedFileRevisionId = (Long) row.get("fileRevisionId");
    final FileRevision fileRevision = session.load(FileRevision.class, loadedFileRevisionId, 3);
    return Optional.of(
        new FileDetailedContext(
            fileRevision,
            (String) row.get("remoteUrl"),
            (String) row.get("repositoryName"),
            (String) row.get("commitHash"),
            (String) row.get("fqn")));
  }
}
