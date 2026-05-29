package net.explorviz.landscape.grpc;

import net.explorviz.landscape.proto.ClassData;

// Transient descriptor for a class awaiting node creation during a batch file-data insert.
// batchKey:        unique key within the current batch, derived from file path and class position.
// parentBatchKey:  batch key of the parent Clazz; null for depth-0 classes.
// parentFileRevId: Neo4j ID of the owning FileRevision; non-null only at depth 0.
// depth:           nesting depth (0 = top-level, 1 = inner, 2 = inner-inner, …).
record PendingClass(
    String batchKey, String parentBatchKey, Long parentFileRevId, int depth, ClassData data) {}
