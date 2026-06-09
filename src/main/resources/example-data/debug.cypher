// example-data/debug.cypher

MERGE (l:Landscape {tokenId: "mytokenvalue"})
MERGE (l)-[:CONTAINS]->(r1:Repository {name: $repoName})
MERGE (l)-[:CONTAINS]->(app:Application {name: $repoName})
MERGE (appRoot:Directory {debugPath: $repoName + "/"})
SET appRoot.name = $repoName
MERGE (app)-[:HAS_ROOT]->(appRoot)
MERGE (r1)-[:HAS_ROOT]->(appRoot);

MATCH (appRoot:Directory {debugPath: $repoName + "/"})
UNWIND [
  {path: "src", parent: "", name: "src"},
  {path: "src/main", parent: "src", name: "main"},
  {path: "src/main/java", parent: "src/main", name: "java"},
  {path: "src/main/java/net", parent: "src/main/java", name: "net"},
  {path: "src/main/java/net/explorviz", parent: "src/main/java/net", name: "explorviz"},
  {path: "src/main/java/net/explorviz/debugsample", parent: "src/main/java/net/explorviz", name: "debugsample"},
  {path: "src/main/java/net/explorviz/debugsample/api", parent: "src/main/java/net/explorviz/debugsample", name: "api"},
  {path: "src/main/java/net/explorviz/debugsample/service", parent: "src/main/java/net/explorviz/debugsample", name: "service"},
  {path: "src/main/java/net/explorviz/debugsample/service/impl", parent: "src/main/java/net/explorviz/debugsample/service", name: "impl"},
  {path: "src/main/java/net/explorviz/debugsample/model", parent: "src/main/java/net/explorviz/debugsample", name: "model"},
  {path: "src/main/java/net/explorviz/debugsample/repository", parent: "src/main/java/net/explorviz/debugsample", name: "repository"},
  {path: "src/main/java/net/explorviz/debugsample/util", parent: "src/main/java/net/explorviz/debugsample", name: "util"},
  {path: "src/main/java/net/explorviz/debugsample/config", parent: "src/main/java/net/explorviz/debugsample", name: "config"},
  {path: "src/test", parent: "src", name: "test"},
  {path: "src/test/java", parent: "src/test", name: "java"},
  {path: "src/test/java/net", parent: "src/test/java", name: "net"},
  {path: "src/test/java/net/explorviz", parent: "src/test/java/net", name: "explorviz"},
  {path: "src/test/java/net/explorviz/debugsample", parent: "src/test/java/net/explorviz", name: "debugsample"},
  {path: "docs", parent: "", name: "docs"},
  {path: "scripts", parent: "", name: "scripts"}
] AS dirSpec
MERGE (dir:Directory {debugPath: $repoName + "/" + dirSpec.path})
SET dir.name = dirSpec.name
WITH appRoot, dirSpec, dir
OPTIONAL MATCH (parent:Directory {debugPath: $repoName + "/" + dirSpec.parent})
FOREACH (_ IN CASE WHEN dirSpec.parent = "" THEN [1] ELSE [] END |
  MERGE (appRoot)-[:CONTAINS]->(dir)
)
FOREACH (_ IN CASE WHEN dirSpec.parent <> "" THEN [1] ELSE [] END |
  MERGE (parent)-[:CONTAINS]->(dir)
);

UNWIND [
  {path: "src/main/java/net/explorviz/debugsample/api/DebugController.java", parent: "src/main/java/net/explorviz/debugsample/api", name: "DebugController.java", functions: ["getSnapshot", "listRuns", "compareSnapshots"]},
  {path: "src/main/java/net/explorviz/debugsample/api/HealthController.java", parent: "src/main/java/net/explorviz/debugsample/api", name: "HealthController.java", functions: ["health", "readVersion"]},
  {path: "src/main/java/net/explorviz/debugsample/service/DebugSessionService.java", parent: "src/main/java/net/explorviz/debugsample/service", name: "DebugSessionService.java", functions: ["startSession", "stopSession", "loadSession"]},
  {path: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java", parent: "src/main/java/net/explorviz/debugsample/service", name: "SnapshotService.java", functions: ["capture", "diff", "normalize"]},
  {path: "src/main/java/net/explorviz/debugsample/service/VariableService.java", parent: "src/main/java/net/explorviz/debugsample/service", name: "VariableService.java", functions: ["resolveVariables", "filterVariables", "groupByInstance"]},
  {path: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java", parent: "src/main/java/net/explorviz/debugsample/service/impl", name: "DefaultDebugSessionService.java", functions: ["createRun", "attachCommit", "closeRun"]},
  {path: "src/main/java/net/explorviz/debugsample/service/impl/DefaultSnapshotService.java", parent: "src/main/java/net/explorviz/debugsample/service/impl", name: "DefaultSnapshotService.java", functions: ["createSnapshot", "storeSnapshot", "dropExpired"]},
  {path: "src/main/java/net/explorviz/debugsample/model/DebugRunDto.java", parent: "src/main/java/net/explorviz/debugsample/model", name: "DebugRunDto.java", functions: ["fromEntity", "toString"]},
  {path: "src/main/java/net/explorviz/debugsample/model/SnapshotDto.java", parent: "src/main/java/net/explorviz/debugsample/model", name: "SnapshotDto.java", functions: ["fromEntity", "withVariables"]},
  {path: "src/main/java/net/explorviz/debugsample/model/VariableDto.java", parent: "src/main/java/net/explorviz/debugsample/model", name: "VariableDto.java", functions: ["fromEntity", "isPrimitive"]},
  {path: "src/main/java/net/explorviz/debugsample/model/UserContext.java", parent: "src/main/java/net/explorviz/debugsample/model", name: "UserContext.java", functions: ["isAdmin", "hasFeature"]},
  {path: "src/main/java/net/explorviz/debugsample/repository/DebugRunRepository.java", parent: "src/main/java/net/explorviz/debugsample/repository", name: "DebugRunRepository.java", functions: ["findByKey", "saveRun"]},
  {path: "src/main/java/net/explorviz/debugsample/repository/SnapshotRepository.java", parent: "src/main/java/net/explorviz/debugsample/repository", name: "SnapshotRepository.java", functions: ["findByRun", "saveSnapshot"]},
  {path: "src/main/java/net/explorviz/debugsample/repository/VariableRepository.java", parent: "src/main/java/net/explorviz/debugsample/repository", name: "VariableRepository.java", functions: ["findBySnapshot", "saveVariable"]},
  {path: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java", parent: "src/main/java/net/explorviz/debugsample/util", name: "VariableComparator.java", functions: ["hasChanged", "sameIdentity", "sameValue"]},
  {path: "src/main/java/net/explorviz/debugsample/util/SnapshotFormatter.java", parent: "src/main/java/net/explorviz/debugsample/util", name: "SnapshotFormatter.java", functions: ["format", "formatValue"]},
  {path: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java", parent: "src/main/java/net/explorviz/debugsample/config", name: "DebugConfig.java", functions: ["enabled", "maxSnapshots"]},
  {path: "src/test/java/net/explorviz/debugsample/DebugSessionServiceTest.java", parent: "src/test/java/net/explorviz/debugsample", name: "DebugSessionServiceTest.java", functions: ["createsRun", "stopsRun"]},
  {path: "src/test/java/net/explorviz/debugsample/SnapshotDiffTest.java", parent: "src/test/java/net/explorviz/debugsample", name: "SnapshotDiffTest.java", functions: ["detectsValueChange", "detectsRemovedVariable"]},
  {path: "docs/debug-flow.md", parent: "docs", name: "debug-flow.md", functions: ["describeDebugFlow"]},
  {path: "scripts/seed-debug-data.sh", parent: "scripts", name: "seed-debug-data.sh", functions: ["seedDebugData"]}
] AS fileSpec
MATCH (parent:Directory {debugPath: $repoName + "/" + fileSpec.parent})
MERGE (file:FileRevision {debugPath: $repoName + "/" + fileSpec.path})
SET file.name = fileSpec.name
MERGE (parent)-[:CONTAINS]->(file)
WITH file, fileSpec
UNWIND fileSpec.functions AS functionName
MERGE (func:Function {functionKey: $repoName + "/" + fileSpec.path + "#" + functionName})
SET func.name = functionName
MERGE (file)-[:CONTAINS]->(func);

MATCH (r1:Repository {name: $repoName})
MERGE (c1:Commit {hash: $repoName + "-commit-1"})
SET c1.authorDate = 1000
MERGE (c2:Commit {hash: $repoName + "-commit-2"})
SET c2.authorDate = 2000
MERGE (c3:Commit {hash: $repoName + "-commit-3"})
SET c3.authorDate = 3000
MERGE (r1)-[:CONTAINS]->(c1)
MERGE (r1)-[:CONTAINS]->(c2)
MERGE (r1)-[:CONTAINS]->(c3)
MERGE (c2)-[:HAS_PARENT]->(c1)
MERGE (c3)-[:HAS_PARENT]->(c2);

MATCH (r1:Repository {name: $repoName})
MATCH (c1:Commit {hash: $repoName + "-commit-1"})
MATCH (c2:Commit {hash: $repoName + "-commit-2"})
MATCH (c3:Commit {hash: $repoName + "-commit-3"})
UNWIND [
  {run: 1, snap: 1, timestamp: 1000000000, line: 41, commit: 1},
  {run: 1, snap: 2, timestamp: 2000000000, line: 41, commit: 1},
  {run: 1, snap: 3, timestamp: 3000000000, line: 87, commit: 1},
  {run: 1, snap: 4, timestamp: 4000000000, line: 87, commit: 2},
  {run: 1, snap: 5, timestamp: 5000000000, line: 132, commit: 2},

  {run: 2, snap: 1, timestamp: 1100000000, line: 55, commit: 2},
  {run: 2, snap: 2, timestamp: 2100000000, line: 55, commit: 2},
  {run: 2, snap: 3, timestamp: 3100000000, line: 144, commit: 2},
  {run: 2, snap: 4, timestamp: 4100000000, line: 144, commit: 3},

  {run: 3, snap: 1, timestamp: 1200000000, line: 21, commit: 3},
  {run: 3, snap: 2, timestamp: 2200000000, line: 21, commit: 3},
  {run: 3, snap: 3, timestamp: 3200000000, line: 76, commit: 3},
  {run: 3, snap: 4, timestamp: 4200000000, line: 76, commit: 3},
  {run: 3, snap: 5, timestamp: 5200000000, line: 201, commit: 3}
] AS snapshotSpec
MERGE (debugRun:DebugRun {debugRunKey: $repoName + "-debug-run-" + snapshotSpec.run})
MERGE (r1)-[:HAS_DEBUG_RUN]->(debugRun)
MERGE (snapshot:DebugSnapshot {
  debugSnapshotKey: $repoName + "-debug-run-" + snapshotSpec.run + "-snapshot-" + snapshotSpec.snap
})
SET snapshot.timestamp = snapshotSpec.timestamp,
    snapshot.lineOfBreakpoint = snapshotSpec.line
MERGE (debugRun)-[:CONTAINS]->(snapshot)
FOREACH (_ IN CASE WHEN snapshotSpec.commit = 1 THEN [1] ELSE [] END |
  MERGE (debugRun)-[:RUNS_ON]->(c1)
)
FOREACH (_ IN CASE WHEN snapshotSpec.commit = 2 THEN [1] ELSE [] END |
  MERGE (debugRun)-[:RUNS_ON]->(c2)
)
FOREACH (_ IN CASE WHEN snapshotSpec.commit = 3 THEN [1] ELSE [] END |
  MERGE (debugRun)-[:RUNS_ON]->(c3)
);

UNWIND [
  // run 1, snapshot 1: baseline
  {run: 1, snap: 1, name: "requestId", instance: "request-1", value: "REQ-1001", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 1, name: "status", instance: "request-1", value: "OPEN", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 1, name: "retryCount", instance: "request-1", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/service/DebugSessionService.java"},
  {run: 1, snap: 1, name: "duration", instance: "timer-1", value: "12.5", type: "float", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 1, name: "precision", instance: "timer-1", value: "12.500001", type: "double", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 1, name: "enabled", instance: "config-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},
  {run: 1, snap: 1, name: "count", instance: "batch-1", value: "4", type: "int", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 1, snap: 1, name: "count", instance: "batch-2", value: "4", type: "int", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 1, snap: 1, name: "label", instance: "user-1", value: "admin", type: "string", file: "src/main/java/net/explorviz/debugsample/model/UserContext.java"},

  // run 1, snapshot 2: unchanged snapshot
  {run: 1, snap: 2, name: "requestId", instance: "request-1", value: "REQ-1001", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 2, name: "status", instance: "request-1", value: "OPEN", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 2, name: "retryCount", instance: "request-1", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/service/DebugSessionService.java"},
  {run: 1, snap: 2, name: "duration", instance: "timer-1", value: "12.5", type: "float", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 2, name: "precision", instance: "timer-1", value: "12.500001", type: "double", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 2, name: "enabled", instance: "config-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},
  {run: 1, snap: 2, name: "count", instance: "batch-1", value: "4", type: "int", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 1, snap: 2, name: "count", instance: "batch-2", value: "4", type: "int", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 1, snap: 2, name: "label", instance: "user-1", value: "admin", type: "string", file: "src/main/java/net/explorviz/debugsample/model/UserContext.java"},

  // run 1, snapshot 3: value changes + new variables
  {run: 1, snap: 3, name: "requestId", instance: "request-1", value: "REQ-1001", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 3, name: "status", instance: "request-1", value: "PROCESSING", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 3, name: "retryCount", instance: "request-1", value: "1", type: "int", file: "src/main/java/net/explorviz/debugsample/service/DebugSessionService.java"},
  {run: 1, snap: 3, name: "duration", instance: "timer-1", value: "18.75", type: "float", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 3, name: "precision", instance: "timer-1", value: "18.750009", type: "double", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 3, name: "enabled", instance: "config-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},
  {run: 1, snap: 3, name: "count", instance: "batch-1", value: "6", type: "int", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 1, snap: 3, name: "count", instance: "batch-2", value: "4", type: "int", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 1, snap: 3, name: "label", instance: "user-1", value: "admin", type: "string", file: "src/main/java/net/explorviz/debugsample/model/UserContext.java"},
  {run: 1, snap: 3, name: "cacheHit", instance: "cache-1", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultSnapshotService.java"},
  {run: 1, snap: 3, name: "threshold", instance: "config-1", value: "0.85", type: "double", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},

  // run 1, snapshot 4: several previous variables no longer captured
  {run: 1, snap: 4, name: "requestId", instance: "request-1", value: "REQ-1001", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 4, name: "status", instance: "request-1", value: "DONE", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 4, name: "retryCount", instance: "request-1", value: "1", type: "int", file: "src/main/java/net/explorviz/debugsample/service/DebugSessionService.java"},
  {run: 1, snap: 4, name: "duration", instance: "timer-1", value: "24.0", type: "float", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 4, name: "precision", instance: "timer-1", value: "24.000001", type: "double", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 4, name: "cacheHit", instance: "cache-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultSnapshotService.java"},

  // run 1, snapshot 5: new request instance, same names
  {run: 1, snap: 5, name: "requestId", instance: "request-2", value: "REQ-1002", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 5, name: "status", instance: "request-2", value: "OPEN", type: "string", file: "src/main/java/net/explorviz/debugsample/api/DebugController.java"},
  {run: 1, snap: 5, name: "retryCount", instance: "request-2", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/service/DebugSessionService.java"},
  {run: 1, snap: 5, name: "duration", instance: "timer-2", value: "3.5", type: "float", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 5, name: "precision", instance: "timer-2", value: "3.500004", type: "double", file: "src/main/java/net/explorviz/debugsample/service/SnapshotService.java"},
  {run: 1, snap: 5, name: "enabled", instance: "config-1", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},

  // run 2
  {run: 2, snap: 1, name: "sessionId", instance: "session-7", value: "S-700", type: "string", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 1, name: "active", instance: "session-7", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 1, name: "openSnapshots", instance: "session-7", value: "1", type: "int", file: "src/main/java/net/explorviz/debugsample/repository/SnapshotRepository.java"},
  {run: 2, snap: 1, name: "load", instance: "worker-1", value: "0.25", type: "float", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 2, snap: 1, name: "load", instance: "worker-2", value: "0.25", type: "float", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},

  {run: 2, snap: 2, name: "sessionId", instance: "session-7", value: "S-700", type: "string", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 2, name: "active", instance: "session-7", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 2, name: "openSnapshots", instance: "session-7", value: "2", type: "int", file: "src/main/java/net/explorviz/debugsample/repository/SnapshotRepository.java"},
  {run: 2, snap: 2, name: "load", instance: "worker-1", value: "0.50", type: "float", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 2, snap: 2, name: "load", instance: "worker-2", value: "0.25", type: "float", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},
  {run: 2, snap: 2, name: "mode", instance: "session-7", value: "TRACE", type: "string", file: "src/main/java/net/explorviz/debugsample/model/DebugRunDto.java"},

  {run: 2, snap: 3, name: "sessionId", instance: "session-7", value: "S-700", type: "string", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 3, name: "active", instance: "session-7", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 3, name: "openSnapshots", instance: "session-7", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/repository/SnapshotRepository.java"},
  {run: 2, snap: 3, name: "mode", instance: "session-7", value: "TRACE", type: "string", file: "src/main/java/net/explorviz/debugsample/model/DebugRunDto.java"},

  {run: 2, snap: 4, name: "sessionId", instance: "session-8", value: "S-701", type: "string", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 4, name: "active", instance: "session-8", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/impl/DefaultDebugSessionService.java"},
  {run: 2, snap: 4, name: "openSnapshots", instance: "session-8", value: "1", type: "int", file: "src/main/java/net/explorviz/debugsample/repository/SnapshotRepository.java"},
  {run: 2, snap: 4, name: "load", instance: "worker-3", value: "0.10", type: "float", file: "src/main/java/net/explorviz/debugsample/service/VariableService.java"},

  // run 3
  {run: 3, snap: 1, name: "leftValue", instance: "comparison-1", value: "alpha", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 1, name: "rightValue", instance: "comparison-1", value: "alpha", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 1, name: "changed", instance: "comparison-1", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 1, name: "score", instance: "comparison-1", value: "1.0", type: "double", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 1, name: "index", instance: "comparison-1", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},

  {run: 3, snap: 2, name: "leftValue", instance: "comparison-1", value: "alpha", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 2, name: "rightValue", instance: "comparison-1", value: "alpha", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 2, name: "changed", instance: "comparison-1", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 2, name: "score", instance: "comparison-1", value: "1.0", type: "double", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 2, name: "index", instance: "comparison-1", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},

  {run: 3, snap: 3, name: "leftValue", instance: "comparison-1", value: "alpha", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 3, name: "rightValue", instance: "comparison-1", value: "beta", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 3, name: "changed", instance: "comparison-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 3, name: "score", instance: "comparison-1", value: "0.42", type: "double", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 3, name: "index", instance: "comparison-1", value: "1", type: "int", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 3, name: "message", instance: "comparison-1", value: "mismatch", type: "string", file: "src/main/java/net/explorviz/debugsample/util/SnapshotFormatter.java"},

  {run: 3, snap: 4, name: "leftValue", instance: "comparison-2", value: "gamma", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 4, name: "rightValue", instance: "comparison-2", value: "gamma", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 4, name: "changed", instance: "comparison-2", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 4, name: "score", instance: "comparison-2", value: "1.0", type: "double", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 4, name: "index", instance: "comparison-2", value: "2", type: "int", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},

  {run: 3, snap: 5, name: "leftValue", instance: "comparison-2", value: "gamma", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 5, name: "rightValue", instance: "comparison-2", value: "delta", type: "string", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 5, name: "changed", instance: "comparison-2", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 5, name: "score", instance: "comparison-2", value: "0.15", type: "double", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 5, name: "index", instance: "comparison-2", value: "3", type: "int", file: "src/main/java/net/explorviz/debugsample/util/VariableComparator.java"},
  {run: 3, snap: 5, name: "message", instance: "comparison-2", value: "changed-after-format", type: "string", file: "src/main/java/net/explorviz/debugsample/util/SnapshotFormatter.java"}
] AS variableSpec
MATCH (snapshot:DebugSnapshot {
  debugSnapshotKey: $repoName + "-debug-run-" + variableSpec.run + "-snapshot-" + variableSpec.snap
})
MATCH (file:FileRevision {debugPath: $repoName + "/" + variableSpec.file})
MERGE (variable:Variable {
  variableKey: $repoName
    + "-debug-run-" + variableSpec.run
    + "-snapshot-" + variableSpec.snap
    + "-var-" + variableSpec.name
    + "-" + variableSpec.instance
})
SET variable.name = variableSpec.name,
    variable.value = variableSpec.value,
    variable.type = variableSpec.type,
    variable.instanceId = variableSpec.instance
MERGE (snapshot)-[:CAPTURES]->(variable)
MERGE (variable)-[:MARKED_IN]->(file);
