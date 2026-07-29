package net.explorviz.landscape.messaging.service.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe functions in code and writes them
 * to the graph.
 */
@ApplicationScoped
public class CodeTelemetryService {

  public void saveEntity(
      final Session session, final TelemetryEntity entity, final CodeDescriptor descriptor) {

    final String[] splitFilePath = descriptor.getFilePath().split("/");
    final String[] splitClassPath =
        descriptor.hasClassName() ? descriptor.getClassName().split("\\.") : new String[0];

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success =
          updateTelemetryIdForExistingFileAndFunction(
              session,
              entity.getLandscapeTokenId(),
              descriptor.getApplicationName(),
              splitFilePath,
              splitClassPath,
              descriptor.getFunctionName(),
              entity.getGitCommitHash(),
              descriptor.getFileId(),
              descriptor.getFunctionId());

      if (success) {
        return;
      }
    }

    ensureFunctionPath(
        session,
        entity.getLandscapeTokenId(),
        descriptor.getApplicationName(),
        splitFilePath,
        splitClassPath,
        descriptor.getFunctionName(),
        descriptor.getFileId(),
        descriptor.getFunctionId());
  }

  private boolean updateTelemetryIdForExistingFileAndFunction(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String[] filePath,
      final String[] classPath,
      final String functionName,
      final String commitHash,
      final String fileTelemetryKey,
      final String funcTelemetryKey) {

    final Function result =
        session.queryForObject(
            Function.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(a:Application {name: $appName})
              -[:HAS_ROOT]->(appRootDir:Directory)

            MATCH (file:FileRevision)
            WHERE
              EXISTS {
                MATCH p = (appRootDir)-[:CONTAINS]->+(file)
                WHERE
                  length(p) = size($filePath) AND
                  all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $filePath[j-1]) AND
                  (:Commit {hash: $commitHash})-[:CONTAINS]->(file)
              }

            MATCH p = (file)
              -[:CONTAINS]->*(:Clazz|FileRevision)
              -[:CONTAINS]->(function:Function {name: $funcName})
            WHERE
              length(p) = size($classPath) + 1 AND
              all(j IN range(1, length(p) - 1) WHERE nodes(p)[j].name = $classPath[j-1])

            SET file.telemetryKey = $fileTelemetryKey
            SET function.telemetryKey = $funcTelemetryKey
            RETURN function;
            """,
            Map.of(
                "tokenId", landscapeToken,
                "appName", applicationName,
                "filePath", filePath,
                "classPath", classPath,
                "commitHash", commitHash,
                "funcName", functionName,
                "fileTelemetryKey", fileTelemetryKey,
                "funcTelemetryKey", funcTelemetryKey));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified function node exists, where the file
   * containing the function should be the runtime version of that file, meaning it should not be
   * contained in any commit. All missing nodes along the path are created, only the landscape node
   * must already exist. If the landscape node is missing, an exception is thrown. For the file and
   * function node, a telemetry key is set regardless of whether the node previously existed or not.
   *
   * @param session OMG session object
   * @param landscapeToken String identifier of the software landscape
   * @param applicationName name of the application within which to search / create function
   * @param filePath path of the file relative to the application node, where the last array element
   *     specifies the file name
   * @param classPath path of the class within the file. Useful for specifying inner classes, e.g.
   *     ["MyClass", "MyInnerClass"]
   * @param functionName name of the function node to search for
   * @param fileTelemetryKey identifier by which to look up telemetry for the file
   * @param functionTelemetryKey identifier by which to look up telemetry for the function
   */
  private void ensureFunctionPath(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String[] filePath,
      final String[] classPath,
      final String functionName,
      final String fileTelemetryKey,
      final String functionTelemetryKey) {

    final Function result =
        session.queryForObject(
            Function.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(app:Application {name: $appName})
            MERGE (app)-[:HAS_ROOT]->(appRoot:Directory)
            ON CREATE SET appRoot.name = "*"

            // Find longest file path match
            MATCH p = (appRoot)-[:CONTAINS]->*(deepestNode:Directory|FileRevision)
            WHERE
              all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $filePath[j-1])
              AND (length(p) < size($filePath) XOR "FileRevision" IN labels(deepestNode))
              AND NOT (:Commit)-[:CONTAINS]->(deepestNode)
            WITH deepestNode, p
            ORDER BY length(p) DESC
            LIMIT 1

            // Create missing directories + file, if any
            WITH *, $filePath[length(p)..] AS remainingFilePath
            OPTIONAL CALL (*) {
              UNWIND [x in range(0, size(remainingFilePath)-1) | x] AS idx
              CREATE (d:Directory {name: remainingFilePath[idx]})
              ORDER BY idx ASC
              WITH collect(d) AS newNodes
              WITH [deepestNode] + newNodes AS nodes
              CALL apoc.nodes.link(nodes, "CONTAINS")
              WITH last(nodes) AS lastCreated
              REMOVE lastCreated:Directory
              SET lastCreated:FileRevision
              RETURN lastCreated
            }
            WITH coalesce(lastCreated, deepestNode) AS file
            SET file.telemetryKey = $fileTelemetryKey

            // Find longest class path match, if a class was specified
            OPTIONAL CALL (file) {
              OPTIONAL MATCH p = (file)-[:CONTAINS]->+(deepestClass:Clazz)
              WHERE all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $classPath[j-1])
              RETURN deepestClass, $classPath[coalesce(length(p), 0)..] AS remainingClassPath
              ORDER BY length(p) DESC
              LIMIT 1
            }
            WITH *, coalesce(deepestClass, file) AS deepestClassOrFile

            // Create missing classes, if any
            OPTIONAL CALL (*) {
              UNWIND [x in range(0, size(remainingClassPath)-1) | x] AS idx
              CREATE (c:Clazz {name: remainingClassPath[idx]})
              ORDER BY idx ASC
              WITH deepestClassOrFile, collect(c) AS newNodes
              WITH [deepestClassOrFile] + newNodes AS nodes
              CALL apoc.nodes.link(nodes, "CONTAINS")
              RETURN last(nodes) AS lastCreatedClass
            }

            WITH *, coalesce(lastCreatedClass, deepestClassOrFile) AS funcParent
            MERGE (funcParent)-[:CONTAINS]->(function:Function {name: $funcName})
            SET function.telemetryKey = $funcTelemetryKey
            RETURN function;
            """,
            Map.of(
                "tokenId", landscapeToken,
                "appName", applicationName,
                "filePath", filePath,
                "classPath", classPath,
                "funcName", functionName,
                "fileTelemetryKey", fileTelemetryKey,
                "funcTelemetryKey", functionTelemetryKey));

    if (result == null) {
      throw new IllegalStateException(
          "Failed to create path to function node. The landscape might be missing.");
    }
  }
}
