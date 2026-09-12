package net.explorviz.landscape.messaging.telemetry.handler;

import java.util.Locale;
import java.util.Map;
import net.explorviz.landscape.ogm.Function;
import net.explorviz.landscape.proto.CodeDescriptor;
import net.explorviz.landscape.proto.TelemetryEntity;
import org.neo4j.ogm.session.Session;

/**
 * Receives entities extracted from telemetry data that describe functions in code and writes them
 * to the graph.
 */
public final class CodeTelemetryHandler {

  private CodeTelemetryHandler() {}

  public static void saveEntity(final Session session, final TelemetryEntity entity) {
    if (!entity.hasCodeDescriptor()) {
      throw new IllegalArgumentException("Code descriptor is required");
    }

    final CodeDescriptor descriptor = entity.getCodeDescriptor();

    if (entity.hasGitCommitHash() && !entity.getGitCommitHash().isEmpty()) {
      final boolean success =
          updateTelemetryKeyForExistingFileAndFunction(session, entity, descriptor);
      if (success) {
        return;
      }
    }

    ensureFunctionPath(session, entity, descriptor);
  }

  /**
   * Retrieves existing file and function nodes from static analysis matching those described by the
   * entity, where the file must be reachable via its application's root directory and be contained
   * within a commit whose hash matches that of the entity. All nodes along the file path must
   * already exist. If a node is found, its telemetry key is set to that of the entity. If no node
   * is found, then no changes to the graph are made.
   *
   * @return True if the file and function existed and the updates were successful, otherwise false.
   */
  private static boolean updateTelemetryKeyForExistingFileAndFunction(
      final Session session, final TelemetryEntity entity, final CodeDescriptor descriptor) {

    final String[] splitFilePath = descriptor.getFilePath().split("/");
    final String[] splitClassPath =
        descriptor.hasClassName() ? descriptor.getClassName().split("\\.") : new String[0];

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
                "tokenId", entity.getLandscapeTokenId(),
                "appName", descriptor.getApplicationName(),
                "filePath", splitFilePath,
                "classPath", splitClassPath,
                "commitHash", entity.getGitCommitHash(),
                "funcName", descriptor.getFunctionName(),
                "scopeName", entity.getInstrumentationScope(),
                "fileTelemetryKey", descriptor.getFileTelemetryKey(),
                "funcTelemetryKey", descriptor.getFunctionTelemetryKey()));

    return result != null;
  }

  /**
   * Ensures that a path from a landscape node to the specified function node exists, where the file
   * containing the function is the runtime version of that file and should be reachable via an
   * instrumentation scope. All missing nodes along the path are created. For the file and function
   * node, a telemetry key is set regardless of whether the node previously existed or not.
   */
  private static void ensureFunctionPath(
      final Session session, final TelemetryEntity entity, final CodeDescriptor descriptor) {

    final String[] splitFilePath = descriptor.getFilePath().split("/");
    final String[] splitClassPath =
        descriptor.hasClassName() ? descriptor.getClassName().split("\\.") : new String[0];

    final Function result =
        session.queryForObject(
            Function.class,
            """
            MERGE (l:Landscape {tokenId: $tokenId})
            MERGE (l)-[:CONTAINS]->(app:Application {name: $appName})
            MERGE (app)-[:HAS_ROOT]->(appRoot:Directory)
            ON CREATE SET appRoot.name = "*"
            MERGE (app)-[:CONTAINS]->(sc:Scope {name: $scopeName})

            // Find longest file path match
            MATCH p = (sc)-[:CONTAINS]->*(deepestNode:Scope|Directory|FileRevision)
            WHERE
              all(j IN range(1, length(p)) WHERE nodes(p)[j].name = $filePath[j-1])
              AND (length(p) < size($filePath) XOR "FileRevision" IN labels(deepestNode))
              AND NOT (:Commit)-[:CONTAINS]->(deepestNode)
            WITH deepestNode, p
            ORDER BY length(p) DESC
            LIMIT 1

            // Create missing directories + file, if necessary
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
              SET lastCreated.language = $language
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
                "tokenId", entity.getLandscapeTokenId(),
                "appName", descriptor.getApplicationName(),
                "scopeName", entity.getInstrumentationScope(),
                "filePath", splitFilePath,
                "classPath", splitClassPath,
                "funcName", descriptor.getFunctionName(),
                "fileTelemetryKey", descriptor.getFileTelemetryKey(),
                "language", descriptor.getLanguage().toUpperCase(Locale.US),
                "funcTelemetryKey", descriptor.getFunctionTelemetryKey()));

    if (result == null) {
      throw new IllegalStateException("Failed to create path to function node.");
    }
  }
}
