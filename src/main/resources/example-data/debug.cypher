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
  {path: "src/main/java/net/explorviz/debugsample/repository", parent: "src/main/java/net/explorviz/debugsample", name: "repository"},
  {path: "src/main/java/net/explorviz/debugsample/config", parent: "src/main/java/net/explorviz/debugsample", name: "config"},
  {path: "src/test", parent: "src", name: "test"},
  {path: "src/test/java", parent: "src/test", name: "java"},
  {path: "src/test/java/net", parent: "src/test/java", name: "net"},
  {path: "src/test/java/net/explorviz", parent: "src/test/java/net", name: "explorviz"},
  {path: "src/test/java/net/explorviz/debugsample", parent: "src/test/java/net/explorviz", name: "debugsample"}
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
  {path: "src/main/java/net/explorviz/debugsample/api/DebugController.java", parent: "src/main/java/net/explorviz/debugsample/api", name: "DebugController.java", functions: ["listRuns", "getSnapshot"]},
  {path: "src/main/java/net/explorviz/debugsample/service/SessionService.java", parent: "src/main/java/net/explorviz/debugsample/service", name: "SessionService.java", functions: ["openSession", "closeSession"]},
  {path: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java", parent: "src/main/java/net/explorviz/debugsample/service", name: "WorkerPool.java", functions: ["schedule", "rebalance"]},
  {path: "src/main/java/net/explorviz/debugsample/service/CacheService.java", parent: "src/main/java/net/explorviz/debugsample/service", name: "CacheService.java", functions: ["get", "put"]},
  {path: "src/main/java/net/explorviz/debugsample/repository/AuditRepository.java", parent: "src/main/java/net/explorviz/debugsample/repository", name: "AuditRepository.java", functions: ["saveEvent", "findLatest"]},
  {path: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java", parent: "src/main/java/net/explorviz/debugsample/config", name: "DebugConfig.java", functions: ["enabled", "threshold"]},
  {path: "src/test/java/net/explorviz/debugsample/SnapshotDiffTest.java", parent: "src/test/java/net/explorviz/debugsample", name: "SnapshotDiffTest.java", functions: ["detectsAllCases"]}
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
MERGE (commit1:Commit {hash: $repoName + "-debug-commit-1"})
SET commit1.authorDate = 1000
MERGE (r1)-[:CONTAINS]->(commit1);

MATCH (r1:Repository {name: $repoName})
MATCH (commit1:Commit {hash: $repoName + "-debug-commit-1"})
MERGE (debugRun:DebugRun {debugRunKey: $repoName + "-debug-run-1"})
MERGE (r1)-[:HAS_DEBUG_RUN]->(debugRun)
MERGE (debugRun)-[:RUNS_ON]->(commit1)
MERGE (snapshotX:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-1-snapshot-1"})
SET snapshotX.timestamp = 1000000000,
    snapshotX.lineOfBreakpoint = 42
MERGE (snapshotY:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-1-snapshot-2"})
SET snapshotY.timestamp = 2000000000,
    snapshotY.lineOfBreakpoint = 84
MERGE (debugRun)-[:CONTAINS]->(snapshotX)
MERGE (debugRun)-[:CONTAINS]->(snapshotY);

UNWIND [
  {snap: 1, name: "workerLoad", instance: "worker-only-in-x-1", value: "0.10", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerMaxLoad", instance: "worker-only-in-x-1", value: "0.900000001", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerHealthy", instance: "worker-only-in-x-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerState", instance: "worker-only-in-x-1", value: "IDLE", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 1, name: "workerLoad", instance: "worker-shared-unchanged", value: "0.25", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerMaxLoad", instance: "worker-shared-unchanged", value: "0.900000002", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerHealthy", instance: "worker-shared-unchanged", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerState", instance: "worker-shared-unchanged", value: "RUNNING", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 1, name: "workerLoad", instance: "worker-shared-changed", value: "0.40", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerMaxLoad", instance: "worker-shared-changed", value: "0.900000003", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerHealthy", instance: "worker-shared-changed", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerState", instance: "worker-shared-changed", value: "RUNNING", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 1, name: "workerLoad", instance: "worker-only-in-x-2", value: "0.70", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerMaxLoad", instance: "worker-only-in-x-2", value: "0.900000004", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerHealthy", instance: "worker-only-in-x-2", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 1, name: "workerState", instance: "worker-only-in-x-2", value: "FAILED", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 2, name: "workerLoad", instance: "worker-shared-unchanged", value: "0.25", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerMaxLoad", instance: "worker-shared-unchanged", value: "0.950000002", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerHealthy", instance: "worker-shared-unchanged", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerState", instance: "worker-shared-unchanged", value: "THROTTLED", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 2, name: "workerLoad", instance: "worker-shared-changed", value: "0.95", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerMaxLoad", instance: "worker-shared-changed", value: "0.990000003", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerHealthy", instance: "worker-shared-changed", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerState", instance: "worker-shared-changed", value: "OVERLOADED", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 2, name: "workerLoad", instance: "worker-only-in-y-1", value: "0.15", type: "float", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerMaxLoad", instance: "worker-only-in-y-1", value: "0.900000005", type: "double", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerHealthy", instance: "worker-only-in-y-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},
  {snap: 2, name: "workerState", instance: "worker-only-in-y-1", value: "STARTING", type: "string", file: "src/main/java/net/explorviz/debugsample/service/WorkerPool.java"},

  {snap: 1, name: "cacheKey", instance: "cache-shared", value: "debug-run-1", type: "string", file: "src/main/java/net/explorviz/debugsample/service/CacheService.java"},
  {snap: 1, name: "cacheTtlOnlyInX", instance: "cache-shared", value: "30", type: "int", file: "src/main/java/net/explorviz/debugsample/service/CacheService.java"},
  {snap: 1, name: "cachePrecisionOnlyInX", instance: "cache-shared", value: "30.000000001", type: "double", file: "src/main/java/net/explorviz/debugsample/service/CacheService.java"},

  {snap: 2, name: "cacheKey", instance: "cache-shared", value: "debug-run-1", type: "string", file: "src/main/java/net/explorviz/debugsample/service/CacheService.java"},
  {snap: 2, name: "cacheHitOnlyInY", instance: "cache-shared", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/CacheService.java"},
  {snap: 2, name: "cacheSizeOnlyInY", instance: "cache-shared", value: "128", type: "int", file: "src/main/java/net/explorviz/debugsample/service/CacheService.java"},

  {snap: 1, name: "sessionStateOnlyInX", instance: "session-only-in-x-1", value: "OPEN", type: "string", file: "src/main/java/net/explorviz/debugsample/service/SessionService.java"},
  {snap: 1, name: "sessionRetryOnlyInX", instance: "session-only-in-x-1", value: "0", type: "int", file: "src/main/java/net/explorviz/debugsample/service/SessionService.java"},
  {snap: 1, name: "sessionActiveOnlyInX", instance: "session-only-in-x-1", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/service/SessionService.java"},

  {snap: 2, name: "auditEventOnlyInY", instance: "audit-only-in-y-1", value: "AUD-2001", type: "string", file: "src/main/java/net/explorviz/debugsample/repository/AuditRepository.java"},
  {snap: 2, name: "auditSequenceOnlyInY", instance: "audit-only-in-y-1", value: "1", type: "int", file: "src/main/java/net/explorviz/debugsample/repository/AuditRepository.java"},

  {snap: 1, name: "configEnabledChanges", instance: "config-shared", value: "true", type: "boolean", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},
  {snap: 1, name: "configThresholdChanges", instance: "config-shared", value: "0.75", type: "float", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},
  {snap: 2, name: "configEnabledChanges", instance: "config-shared", value: "false", type: "boolean", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},
  {snap: 2, name: "configThresholdChanges", instance: "config-shared", value: "0.90", type: "float", file: "src/main/java/net/explorviz/debugsample/config/DebugConfig.java"},

  {snap: 1, name: "assertionCountChanges", instance: "test-shared", value: "5", type: "int", file: "src/test/java/net/explorviz/debugsample/SnapshotDiffTest.java"},
  {snap: 2, name: "assertionCountChanges", instance: "test-shared", value: "7", type: "int", file: "src/test/java/net/explorviz/debugsample/SnapshotDiffTest.java"}
] AS variableSpec
MATCH (snapshot:DebugSnapshot {
  debugSnapshotKey: $repoName + "-debug-run-1-snapshot-" + variableSpec.snap
})
MATCH (file:FileRevision {debugPath: $repoName + "/" + variableSpec.file})
MERGE (variable:Variable {
  variableKey: $repoName
    + "-debug-run-1"
    + "-snapshot-" + variableSpec.snap
    + "-file-" + variableSpec.file
    + "-instance-" + variableSpec.instance
    + "-var-" + variableSpec.name
})
SET variable.name = variableSpec.name,
    variable.value = variableSpec.value,
    variable.type = variableSpec.type,
    variable.instanceId = variableSpec.instance
MERGE (snapshot)-[:CAPTURES]->(variable)
MERGE (variable)-[:MARKED_IN]->(file);
