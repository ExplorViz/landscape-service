package net.explorviz.landscape.repository;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import net.explorviz.landscape.ogm.Directory;
import org.jboss.logging.Logger;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class DirectoryRepository {
  private static final Logger LOGGER = Logger.getLogger(DirectoryRepository.class);
  private static final String FIND_LONGEST_PATH_MATCH_STATIC_DATA =
      """
      WITH $pathSegments AS pathSegments
      MATCH (l:Landscape {tokenId: $tokenId})
        -[:CONTAINS]->(r:Repository {name: $repoName})
        -[:HAS_ROOT]->(rd:Directory {name: $repoName})
      OPTIONAL MATCH p=(rd)-[:CONTAINS]->*(:Directory)
      WHERE all(
        j in range(0,length(p))
        WHERE nodes(p)[j].name = pathSegments[j]
      )
      RETURN coalesce(last(nodes(p)), rd) AS existingDir,
             pathSegments[coalesce(length(p)+1, 0)..] AS remainingPath
      ORDER BY size(nodes(p)) DESC
      LIMIT 1;
      """;

  private Map<String, Object> findLongestPathMatchStaticData(
      final Session session,
      final String[] pathSegments,
      final String repoName,
      final String landscapeTokenId) {
    final Result result =
        session.query(
            FIND_LONGEST_PATH_MATCH_STATIC_DATA,
            Map.of(
                "tokenId", landscapeTokenId, "repoName", repoName, "pathSegments", pathSegments));

    final Iterator<Map<String, Object>> resultIterator = result.queryResults().iterator();
    if (!resultIterator.hasNext()) {
      throw new NoSuchElementException("resultIterator empty");
    }

    return resultIterator.next();
  }

  public Long getRepositoryRootDirectoryId(
      final Session session, final String landscapeTokenId, final String repoName) {
    return session.queryForObject(
        Long.class,
        """
        MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(:Repository {name: $repoName})
          -[:HAS_ROOT]->(root:Directory {name: $repoName})
        RETURN id(root)
        LIMIT 1
        """,
        Map.of("tokenId", landscapeTokenId, "repoName", repoName));
  }

  public Long mergeDirectoryPathFromRoot(
      final Session session,
      final Long rootDirectoryId,
      final String[] directorySegments,
      final String directoryKey,
      final Map<String, Long> directoryLeafCache) {
    final Long cached = directoryLeafCache.get(directoryKey);
    if (cached != null) {
      return cached;
    }

    Long parentId = rootDirectoryId;
    for (int i = 1; i < directorySegments.length; i++) {
      parentId = mergeChildDirectory(session, parentId, directorySegments[i]);
    }
    directoryLeafCache.put(directoryKey, parentId);
    return parentId;
  }

  private Long mergeChildDirectory(
      final Session session, final Long parentId, final String directoryName) {
    final Result result =
        session.query(
            """
            MATCH (parent:Directory) WHERE id(parent) = $parentId
            MERGE (parent)-[:CONTAINS]->(child:Directory {name: $directoryName})
            RETURN id(child) AS childId
            """,
            Map.of("parentId", parentId, "directoryName", directoryName));

    final Iterator<Map<String, Object>> rows = result.queryResults().iterator();
    if (!rows.hasNext()) {
      throw new NoSuchElementException("Failed to merge directory '" + directoryName + "'");
    }
    return (Long) rows.next().get("childId");
  }

  public Directory createDirectoryStructureAndReturnLastDirStaticData(
      final Session session,
      final String[] filePath,
      final String repoName,
      final String landscapeTokenId) {
    final Map<String, Object> resultMap =
        findLongestPathMatchStaticData(session, filePath, repoName, landscapeTokenId);
    final Directory existingDir =
        resultMap.get("existingDir") instanceof Directory dir ? dir : null;
    if (existingDir == null) {
      throw new NoSuchElementException("No existing directory found");
    }

    final String[] remainingPath =
        resultMap.get("remainingPath") instanceof String[] rp ? rp : new String[0];

    Directory lastDir = existingDir;
    for (final String dirName : remainingPath) {
      final Directory newDir = new Directory(dirName);
      lastDir.addSubdirectory(newDir);
      lastDir = newDir;
    }
    if (remainingPath.length > 0) {
      session.save(existingDir);
    }

    return lastDir;
  }

  /**
   * Merges all directory nodes for a batch of file paths in a single pass per depth level.
   *
   * <p>Instead of issuing one Cypher round-trip per path segment (as {@link
   * #mergeDirectoryPathFromRoot} does), this method groups all unique directory paths by depth and
   * issues a single {@code UNWIND} query per depth level. For a maximum tree depth of D, only D-1
   * round-trips are needed regardless of how many distinct paths are in the batch.
   *
   * <p>On completion, every entry in {@code uniqueDirPaths} has its leaf directory ID stored in
   * {@code directoryLeafCache} using the joined path string as key (same key format as {@link
   * #mergeDirectoryPathFromRoot}).
   *
   * @param session OGM session
   * @param rootDirectoryId Neo4j internal ID of the repository root directory
   * @param rootKey Cache key for the root directory (typically the repository name)
   * @param uniqueDirPaths Map from joined path string to its path-segment array; must include all
   *     ancestor paths for every leaf directory that needs to be created
   * @param directoryLeafCache Shared cache populated with (path → node ID) entries
   */
  public void batchMergeDirectoryPaths(
      final Session session,
      final Long rootDirectoryId,
      final String rootKey,
      final Map<String, String[]> uniqueDirPaths,
      final Map<String, Long> directoryLeafCache) {

    directoryLeafCache.putIfAbsent(rootKey, rootDirectoryId);

    final int maxDepth =
        uniqueDirPaths.values().stream().mapToInt(segs -> segs.length).max().orElse(0);

    for (int targetLen = 2; targetLen <= maxDepth; targetLen++) {
      final List<Map<String, Object>> dirsAtDepth = new ArrayList<>();

      for (final Map.Entry<String, String[]> entry : uniqueDirPaths.entrySet()) {
        final String[] segs = entry.getValue();
        if (segs.length != targetLen || directoryLeafCache.containsKey(entry.getKey())) {
          continue;
        }
        final String parentKey = String.join("/", Arrays.copyOfRange(segs, 0, segs.length - 1));
        final Long parentId = directoryLeafCache.get(parentKey);
        if (parentId == null) {
          LOGGER.warnf(
              "Parent directory '%s' not found in cache while creating '%s'; skipping",
              parentKey, entry.getKey());
          continue;
        }
        dirsAtDepth.add(
            Map.of(
                "parentId", parentId, "name", segs[segs.length - 1], "fullPath", entry.getKey()));
      }

      if (dirsAtDepth.isEmpty()) {
        continue;
      }

      final Result result =
          session.query(
              """
              UNWIND $dirs AS dir
              MATCH (parent:Directory) WHERE id(parent) = dir.parentId
              MERGE (parent)-[:CONTAINS]->(child:Directory {name: dir.name})
              ON CREATE SET child.fullPath = dir.fullPath
              RETURN dir.fullPath AS fullPath, id(child) AS childId
              """,
              Map.of("dirs", dirsAtDepth));

      result
          .queryResults()
          .forEach(
              row ->
                  directoryLeafCache.put((String) row.get("fullPath"), (Long) row.get("childId")));
    }
  }

  /**
   * Moves all directories and files from the source directory to the target directory. If a
   * directory or file with the same relative path is already present in the target directory, then
   * the node is not moved. An exception to this is if a file is already present under the same
   * name, but not with the same hash; in this case, the file is also moved. The source directory
   * and any children which have an equivalent node already present in the target directory are
   * deleted after the merge.
   *
   * @param session OGM session object
   * @param sourceDirectoryId ID of the directory node whose child nodes to migrate
   * @param destinationDirectoryId ID of the target node into which the source's child nodes should
   *     be reparented
   */
  public void mergeDirectories(
      final Session session, final long sourceDirectoryId, final long destinationDirectoryId) {
    session.query(
        """
        MATCH (src:Directory) WHERE id(src) = $sourceDirId
        MATCH (dst:Directory) WHERE id(dst) = $destinationDirId
        MATCH p1 = (src)-[:CONTAINS]->*(:Directory)-[r:CONTAINS]->(notInDst:Directory|FileRevision)
        MATCH p2 = (dst)-[:CONTAINS]->*(dstParent:Directory)
        WHERE
         length(p2) = length(p1) - 1 AND
         all(i IN range(1, length(p2)) WHERE nodes(p1)[i].name = nodes(p2)[i].name) AND
         NOT EXISTS {
           MATCH (dstParent)-[:CONTAINS]->(dstChild:Directory)
           WHERE
             dstChild.name = notInDst.name AND
             labels(dstChild) = labels(notInDst) AND
             coalesce(dstChild.hash, "NONE") = coalesce(notInDst.hash, "NONE")
         }
        DELETE r
        MERGE (dstParent)-[:CONTAINS]->(notInDst)
        MATCH (src)-[:CONTAINS*0..]->(n)
        WHERE NOT (dst)-[:CONTAINS*0..]->(n)
        DETACH DELETE n;
        """,
        Map.of("sourceDirId", sourceDirectoryId, "destinationDirId", destinationDirectoryId));
  }
}
