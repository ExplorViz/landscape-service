package net.explorviz.persistence.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Stream;
import net.explorviz.persistence.ogm.Application;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.Directory;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.proto.FileIdentifier;
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
  private static final Logger LOGGER = Logger.getLogger(FileRevisionRepository.class);

  @Inject SessionFactory sessionFactory;
  @Inject ApplicationRepository applicationRepository;
  @Inject DirectoryRepository directoryRepository;
  @Inject LandscapeRepository landscapeRepository;

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
   *     "explorviz", "persistence", "MyClass.java"]
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
   *     "explorviz", "persistence", "MyClass.java"]
   * @return The newly created FileRevision according to the provided FQN
   */
  public FileRevision createFileStructureForNewApplicationFromFqn(
      final Session session, final Application application, final String[] splitFileFqn) {

    final Directory rootDir = new Directory(Application.ROOT_NAME_PLACEHOLDER_RUNTIME);
    application.setRootDirectory(rootDir);
    session.save(application);
    return createRemainingFilePath(session, rootDir, splitFileFqn);
  }

  public FileRevision createFileStructureFromStaticData(
      final Session session,
      final FileIdentifier fileIdentifier,
      final String repoName,
      final String landscapeTokenId,
      final Commit commit) {
    final String[] pathSegments = fileIdentifier.getFilePath().split("/");
    String[] directorySegments = {repoName};
    if (pathSegments.length > 1) {
      directorySegments = Arrays.copyOfRange(pathSegments, 0, pathSegments.length - 1);
      directorySegments =
          Stream.concat(Stream.of(repoName), Arrays.stream(directorySegments))
              .toArray(String[]::new);
    }

    FileRevision file =
        getFileRevisionFromHashAndPath(
                session, fileIdentifier.getFileHash(), repoName, landscapeTokenId, pathSegments)
            .orElse(null);
    if (file == null) {
      file = new FileRevision(pathSegments[pathSegments.length - 1], fileIdentifier.getFileHash());
    }

    commit.addFileRevision(file);

    final Directory parentDir =
        directoryRepository.createDirectoryStructureAndReturnLastDirStaticData(
            session, directorySegments, repoName, landscapeTokenId);
    parentDir.addFileRevision(file);

    session.save(List.of(parentDir, commit, file));

    return file;
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

  private void validateFqn(final String[] splitFqn) {
    if (splitFqn.length == 0) {
      throw new IllegalArgumentException("FQN must not be empty");
    }
  }
}
