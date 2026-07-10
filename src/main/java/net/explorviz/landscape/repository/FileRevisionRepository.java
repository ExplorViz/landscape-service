package net.explorviz.landscape.repository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import net.explorviz.landscape.ogm.Application;
import net.explorviz.landscape.ogm.Commit;
import net.explorviz.landscape.ogm.Directory;
import net.explorviz.landscape.ogm.FileRevision;
import net.explorviz.landscape.proto.FileIdentifier;
import org.jboss.logging.Logger;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
public class FileRevisionRepository {

  private static final String FIND_LONGEST_PATH_MATCH_FOR_FQN_WITHOUT_COMMIT =
      """
      MATCH (l:Landscape {tokenId: $tokenId})
        -[:CONTAINS]->(app:Application {name: $appName})
        -[:HAS_ROOT]->(appRootDir:Directory)
      OPTIONAL MATCH p = (fqnRoot:Directory|FileRevision)
        -[:CONTAINS]->*(lastNode:Directory|FileRevision)
      WHERE
        (appRootDir)-[:CONTAINS]->(fqnRoot) AND
        all(j IN range(0, length(p)) WHERE nodes(p)[j].name = $pathSegments[j]) AND
        (size(nodes(p)) < size($pathSegments) XOR "FileRevision" IN labels(lastNode)) AND
        size(nodes(p)) <= size($pathSegments) AND
        NOT (:Commit)-[:CONTAINS]->(lastNode)
      RETURN
        coalesce(lastNode, appRootDir) AS existingNode,
        $pathSegments[coalesce(length(p)+1, 0)..] AS remainingPath
      ORDER BY length(p) DESC
      LIMIT 1;
      """;
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

  @Inject SessionFactory sessionFactory;
  @Inject ApplicationRepository applicationRepository;
  @Inject DirectoryRepository directoryRepository;
  @Inject LandscapeRepository landscapeRepository;
  @Inject CommitFileStructureBatchWriter commitFileStructureBatchWriter;
  @Inject UnchangedCommitFileCopier unchangedCommitFileCopier;
  @Inject CommitRepository commitRepository;

  private FileRevision createRemainingFilePath(
      final Session session, final Directory startingDirectory, final String[] remainingPath) {
    Directory currentDirectory = startingDirectory;
    for (int i = 0; i < remainingPath.length - 1; i++) {
      final Directory newDirectory = new Directory(remainingPath[i]);
      currentDirectory.addSubdirectory(newDirectory);
      currentDirectory = newDirectory;
    }

    final FileRevision file = new FileRevision(remainingPath[remainingPath.length - 1]);
    currentDirectory.addFileRevision(file);
    session.save(startingDirectory);
    return file;
  }

  private Map<String, Object> findLongestPathMatchForFqn(
      final Session session,
      final String[] fileFqn,
      final String applicationName,
      final String landscapeToken) {

    final Result result =
        session.query(
            FIND_LONGEST_PATH_MATCH_FOR_FQN_WITHOUT_COMMIT,
            Map.of("pathSegments", fileFqn, "appName", applicationName, "tokenId", landscapeToken));

    final Iterator<Map<String, Object>> resultIterator = result.queryResults().iterator();
    if (!resultIterator.hasNext()) {
      throw new NoSuchElementException("resultIterator empty");
    }

    return resultIterator.next();
  }

  /**
   * Create any missing Directory / FileRevision nodes according to the provided FQN for an existing
   * Application object which is already connected to the Landscape graph.
   *
   * @param session OGM session object.
   * @param applicationName Name of an existing application which is already connected to the
   *     Landscape with the given token.
   * @param splitFileFqn File FQN starting from application root (not inclusive), e.g. ["net",
   *     "explorviz", "landscape", "MyClass.java"]
   * @return The existing or newly created FileRevision according to the provided FQN
   */
  public FileRevision createFileStructureForExistingApplicationFromFileFqn(
      final Session session,
      final String[] splitFileFqn,
      final String applicationName,
      final String landscapeToken) {

    validateFqn(splitFileFqn);

    final Map<String, Object> resultMap =
        findLongestPathMatchForFqn(session, splitFileFqn, applicationName, landscapeToken);

    final String[] remainingPath =
        resultMap.get("remainingPath") instanceof String[] p ? p : new String[0];
    if (remainingPath.length == 0) {
      if (resultMap.get("existingNode") instanceof FileRevision fileRev) {
        return fileRev;
      }
      throw new NoSuchElementException("remainingPath is length 0, but result is not FileRevision");
    }

    final Directory startingDirectory =
        resultMap.get("existingNode") instanceof Directory dir ? dir : null;
    if (startingDirectory == null) {
      // Root directory not matched, application does not exist or has no root
      throw new NoSuchElementException("startingDirectory is null. Does the application exist?");
    }

    return createRemainingFilePath(session, startingDirectory, remainingPath);
  }

  /**
   * Create Directory / FileRevision nodes according to the provided FQN for a newly created
   * Application object which is not yet connected to the Landscape graph.
   *
   * @param session OGM session object.
   * @param application Newly created application object, assumed not to have a root directory.
   * @param splitFileFqn File FQN starting from application root (not inclusive), e.g. ["net",
   *     "explorviz", "landscape", "MyClass.java"]
   * @return The newly created FileRevision according to the provided FQN
   */
  public FileRevision createFileStructureForNewApplicationFromFqn(
      final Session session, final Application application, final String[] splitFileFqn) {

    final Directory rootDir = new Directory(Application.ROOT_NAME_PLACEHOLDER_RUNTIME);
    application.setRootDirectory(rootDir);
    session.save(application);
    return createRemainingFilePath(session, rootDir, splitFileFqn);
  }

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
        throw new ParentCommitNotReadyException(
            String.format(
                "Parent commit %s is not yet available in repository '%s'; cannot inherit"
                    + " unchanged files for child %d",
                request.parentCommitHash(), request.repoName(), request.childCommitInternalId()));
      }
      Log.debugf(
          "Parent commit %s not found in repository '%s'; skipping unchanged-file copy to child %d",
          request.parentCommitHash(), request.repoName(), request.childCommitInternalId());
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
   * Retrieve the FileRevision matching the specified path starting in the given application. The
   * file must additionally be part of a commit with the given hash, otherwise nothing is matched.
   *
   * @param session OGM session object
   * @param repoName Name of the repository in which to search
   * @param commitHash Hash of the git commit to which the file must belong
   * @param pathSegments List of directory names + the file name, beginning at the application's
   *     root directory
   * @param landscapeToken Token ID of the landscape in which to search
   * @return An Optional describing the specified FileRevision. Empty if no FileRevision is matched.
   */
  public Optional<FileRevision> findFileRevisionFromRepoNameAndCommitHashAndPath(
      final Session session,
      final String repoName,
      final String commitHash,
      final String[] pathSegments,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            FileRevision.class,
            """
                MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Repository {name: $repoName})
                  -[:HAS_ROOT]->(repoRootDir:Directory)
                MATCH p = (repoRootDir)-[:CONTAINS]->*(file:FileRevision)
                WHERE
                  length(p) = size($pathSegments) AND
                  all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $pathSegments[j-1]) AND
                  EXISTS {
                    (:Commit {hash: $commitHash})-[:CONTAINS]->(file)
                  }
                OPTIONAL MATCH (file)-[r:CONTAINS*0..3]->(sub)
                RETURN file, r, sub;
            """,
            Map.of(
                "tokenId",
                landscapeToken,
                "repoName",
                repoName,
                "pathSegments",
                pathSegments,
                "commitHash",
                commitHash)));
  }

  public Optional<FileRevision> findFileRevisionFromAppNameAndCommitHashAndPath(
      final Session session,
      final String applicationName,
      final String commitHash,
      final String[] pathSegments,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            FileRevision.class,
            """
                MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Application {name: $appName})
                  -[:HAS_ROOT]->(appRootDir:Directory)
                MATCH p = (appRootDir)-[:CONTAINS]->*(file:FileRevision)
                WHERE
                  length(p) = size($pathSegments) AND
                  all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $pathSegments[j-1]) AND
                  EXISTS {
                    (:Commit {hash: $commitHash})-[:CONTAINS]->(file)
                  }
                RETURN file;
            """,
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "pathSegments",
                pathSegments,
                "commitHash",
                commitHash)));
  }

  public Optional<FileRevision> findFileRevisionFromAppNameAndPathWithoutCommit(
      final Session session,
      final String applicationName,
      final String[] pathSegments,
      final String landscapeToken) {
    return Optional.ofNullable(
        session.queryForObject(
            FileRevision.class,
            """
                MATCH (l:Landscape {tokenId: $tokenId})
                  -[:CONTAINS]->(:Application {name: $appName})
                  -[:HAS_ROOT]->(appRootDir:Directory)
                MATCH p = (appRootDir)-[:CONTAINS]->*(file:FileRevision)
                WHERE
                  length(p) = size($pathSegments) AND
                  all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $pathSegments[j-1]) AND
                  NOT (:Commit)-[:CONTAINS]->(file)
                RETURN file;
            """,
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
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

  private void validateFqn(final String[] splitFqn) {
    if (splitFqn.length == 0) {
      throw new IllegalArgumentException("FQN must not be empty");
    }
  }
}
