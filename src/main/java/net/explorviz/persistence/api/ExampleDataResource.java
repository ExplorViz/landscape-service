package net.explorviz.persistence.api;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.Path;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.explorviz.persistence.ogm.Application;
import net.explorviz.persistence.ogm.Branch;
import net.explorviz.persistence.ogm.Clazz;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.Directory;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.ogm.Function;
import net.explorviz.persistence.ogm.Landscape;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.ogm.Span;
import net.explorviz.persistence.ogm.Trace;
import net.explorviz.persistence.ogm.Variable;
import org.jboss.resteasy.reactive.RestQuery;
import org.neo4j.ogm.model.Result;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

/**
 * Contains dev-exclusive endpoints for populating the database with testing data without having to
 * run other ExplorViz services. Simply cURL endpoints or access them via browser.
 */
@IfBuildProfile("dev")
@Path("/example")
@SuppressWarnings({"PMD.NcssCount", "PMD.TooManyMethods"})
public class ExampleDataResource {

  @Inject SessionFactory sessionFactory;

  @GET
  @Path("/debug")
  public String createTestingDebugData(@RestQuery final String name) {
    final Session session = sessionFactory.openSession();
    final String repoName = name != null ? name : "hello-world";
    session.query(
        """
        MERGE (l:Landscape {tokenId: "mytokenvalue"})
        MERGE (l)-[:CONTAINS]->(r1:Repository {name: $repoName})
        MERGE (l)-[:CONTAINS]->(app:Application {name: "hello-world"})

        MERGE (app)-[:HAS_ROOT]->(appRoot:Directory {name: "hello-world"})
        MERGE (appRoot)-[:CONTAINS]->(d1:Directory {name: "net"})
        MERGE (d1)-[:CONTAINS]->(d2:Directory {name: "explorviz"})
        MERGE (d2)-[:CONTAINS]->(outerDir:Directory {name: "helloworld"})

        MERGE (outerDir)-[:CONTAINS]->(innerDir:Directory {name: "innerpackage"})
        MERGE (outerDir)-[:CONTAINS]->(serviceDir:Directory {name: "service"})
        MERGE (outerDir)-[:CONTAINS]->(modelDir:Directory {name: "model"})
        MERGE (outerDir)-[:CONTAINS]->(utilDir:Directory {name: "util"})
        MERGE (serviceDir)-[:CONTAINS]->(apiDir:Directory {name: "api"})

        MERGE (outerDir)-[:CONTAINS]->(file1:FileRevision {name: "File1.java"})
        MERGE (outerDir)-[:CONTAINS]->(file2:FileRevision {name: "File2.java"})
        MERGE (innerDir)-[:CONTAINS]->(file3:FileRevision {name: "File3.java"})
        MERGE (serviceDir)-[:CONTAINS]->(file4:FileRevision {name: "File4.java"})
        MERGE (serviceDir)-[:CONTAINS]->(file5:FileRevision {name: "File5.java"})
        MERGE (modelDir)-[:CONTAINS]->(file6:FileRevision {name: "File6.java"})
        MERGE (utilDir)-[:CONTAINS]->(file7:FileRevision {name: "File7.java"})
        MERGE (apiDir)-[:CONTAINS]->(file8:FileRevision {name: "File8.java"})

        MERGE (file1)-[:CONTAINS]->(func1:Function {name: "function1"})
        MERGE (file2)-[:CONTAINS]->(func2:Function {name: "function2"})
        MERGE (file3)-[:CONTAINS]->(func3:Function {name: "function3"})
        MERGE (file4)-[:CONTAINS]->(func4:Function {name: "function4"})
        MERGE (file5)-[:CONTAINS]->(func5:Function {name: "function5"})
        MERGE (file6)-[:CONTAINS]->(func6:Function {name: "function6"})
        MERGE (file7)-[:CONTAINS]->(func7:Function {name: "function7"})
        MERGE (file8)-[:CONTAINS]->(func8:Function {name: "function8"})

        MERGE (r1)-[:HAS_ROOT]->(appRoot)

        MERGE (dr1:DebugRun {debugRunKey: $repoName + "-debug-run-1"})
        MERGE (dr2:DebugRun {debugRunKey: $repoName + "-debug-run-2"})
        MERGE (dr3:DebugRun {debugRunKey: $repoName + "-debug-run-3"})

        MERGE (r1)-[:HAS_DEBUG_RUN]->(dr1)
        MERGE (r1)-[:HAS_DEBUG_RUN]->(dr2)
        MERGE (r1)-[:HAS_DEBUG_RUN]->(dr3)

        MERGE (c1:Commit {hash: "commit1"})
        MERGE (c2:Commit {hash: "commit2"})

        MERGE (r1)-[:CONTAINS]->(c1)
        MERGE (r1)-[:CONTAINS]->(c2)

        MERGE (dr1)-[:RUNS_ON]->(c1)
        MERGE (dr2)-[:RUNS_ON]->(c1)
        MERGE (dr3)-[:RUNS_ON]->(c2)

        MERGE (ds11:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-1-snapshot-1"})
        SET ds11.timestamp = 1000000000,
        ds11.lineOfBreakpoint = 42

        MERGE (ds12:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-1-snapshot-2"})
        SET ds12.timestamp = 2000000000,
        ds12.lineOfBreakpoint = 142

        MERGE (ds13:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-1-snapshot-3"})
        SET ds13.timestamp = 3000000000,
        ds13.lineOfBreakpoint = 42

        MERGE (dr1)-[:CONTAINS]->(ds11)
        MERGE (dr1)-[:CONTAINS]->(ds12)
        MERGE (dr1)-[:CONTAINS]->(ds13)

        MERGE (ds21:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-2-snapshot-1"})
        SET ds21.timestamp = 1000000000,
        ds21.lineOfBreakpoint = 42

        MERGE (ds22:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-2-snapshot-2"})
        SET ds22.timestamp = 2000000000,
        ds22.lineOfBreakpoint = 142

        MERGE (dr2)-[:CONTAINS]->(ds21)
        MERGE (dr2)-[:CONTAINS]->(ds22)

        MERGE (ds31:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-3-snapshot-1"})
        SET ds31.timestamp = 1000000000,
        ds31.lineOfBreakpoint = 42

        MERGE (ds32:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-3-snapshot-2"})
        SET ds32.timestamp = 2000000000,
        ds32.lineOfBreakpoint = 142

        MERGE (ds33:DebugSnapshot {debugSnapshotKey: $repoName + "-debug-run-3-snapshot-3"})
        SET ds33.timestamp = 3000000000,
        ds33.lineOfBreakpoint = 142

        MERGE (dr3)-[:CONTAINS]->(ds31)
        MERGE (dr3)-[:CONTAINS]->(ds32)
        MERGE (dr3)-[:CONTAINS]->(ds33)

        /* debug-run-1, snapshot-1 */
        MERGE (var11aA:Variable {variableKey: $repoName + "-debug-run-1-snapshot-1-var-a-object-a"})
        SET var11aA.name = "a",
        var11aA.value = "42",
        var11aA.type = "int",
        var11aA.instanceId = "object-a"

        MERGE (var11aB:Variable {variableKey: $repoName + "-debug-run-1-snapshot-1-var-a-object-b"})
        SET var11aB.name = "a",
        var11aB.value = "84",
        var11aB.type = "int",
        var11aB.instanceId = "object-b"

        MERGE (var11bD:Variable {variableKey: $repoName + "-debug-run-1-snapshot-1-var-b-object-d"})
        SET var11bD.name = "b",
        var11bD.value = "15.5",
        var11bD.type = "double",
        var11bD.instanceId = "object-d"

        MERGE (var11cE:Variable {variableKey: $repoName + "-debug-run-1-snapshot-1-var-c-object-e"})
        SET var11cE.name = "c",
        var11cE.value = "false",
        var11cE.type = "boolean",
        var11cE.instanceId = "object-e"

        /* debug-run-1, snapshot-2 */
        MERGE (var12aA:Variable {variableKey: $repoName + "-debug-run-1-snapshot-2-var-a-object-a"})
        SET var12aA.name = "a",
        var12aA.value = "100",
        var12aA.type = "int",
        var12aA.instanceId = "object-a"

        MERGE (var12bA:Variable {variableKey: $repoName + "-debug-run-1-snapshot-2-var-b-object-a"})
        SET var12bA.name = "b",
        var12bA.value = "999",
        var12bA.type = "int",
        var12bA.instanceId = "object-a"

        MERGE (var12cD:Variable {variableKey: $repoName + "-debug-run-1-snapshot-2-var-c-object-d"})
        SET var12cD.name = "c",
        var12cD.value = "27.25",
        var12cD.type = "float",
        var12cD.instanceId = "object-d"

        MERGE (var12dF:Variable {variableKey: $repoName + "-debug-run-1-snapshot-2-var-d-object-f"})
        SET var12dF.name = "d",
        var12dF.value = "Alice",
        var12dF.type = "string",
        var12dF.instanceId = "object-f"

        /* debug-run-1, snapshot-3 */
        MERGE (var13aA:Variable {variableKey: $repoName + "-debug-run-1-snapshot-3-var-a-object-a"})
        SET var13aA.name = "a",
        var13aA.value = "true",
        var13aA.type = "boolean",
        var13aA.instanceId = "object-a"

        MERGE (var13bA:Variable {variableKey: $repoName + "-debug-run-1-snapshot-3-var-b-object-a"})
        SET var13bA.name = "b",
        var13bA.value = "333",
        var13bA.type = "int",
        var13bA.instanceId = "object-a"

        MERGE (var13cD:Variable {variableKey: $repoName + "-debug-run-1-snapshot-3-var-c-object-d"})
        SET var13cD.name = "c",
        var13cD.value = "31.75",
        var13cD.type = "double",
        var13cD.instanceId = "object-d"

        MERGE (var13dG:Variable {variableKey: $repoName + "-debug-run-1-snapshot-3-var-d-object-g"})
        SET var13dG.name = "d",
        var13dG.value = "OK",
        var13dG.type = "string",
        var13dG.instanceId = "object-g"

        /* debug-run-2, snapshot-1 */
        MERGE (var21aA:Variable {variableKey: $repoName + "-debug-run-2-snapshot-1-var-a-object-a"})
        SET var21aA.name = "a",
        var21aA.value = "42",
        var21aA.type = "int",
        var21aA.instanceId = "object-a"

        MERGE (var21bA:Variable {variableKey: $repoName + "-debug-run-2-snapshot-1-var-b-object-a"})
        SET var21bA.name = "b",
        var21bA.value = "999",
        var21bA.type = "int",
        var21bA.instanceId = "object-a"

        MERGE (var21cB:Variable {variableKey: $repoName + "-debug-run-2-snapshot-1-var-c-object-b"})
        SET var21cB.name = "c",
        var21cB.value = "true",
        var21cB.type = "boolean",
        var21cB.instanceId = "object-b"

        MERGE (var21dH:Variable {variableKey: $repoName + "-debug-run-2-snapshot-1-var-d-object-h"})
        SET var21dH.name = "d",
        var21dH.value = "128.5",
        var21dH.type = "float",
        var21dH.instanceId = "object-h"

        /* debug-run-2, snapshot-2 */
        MERGE (var22aA:Variable {variableKey: $repoName + "-debug-run-2-snapshot-2-var-a-object-a"})
        SET var22aA.name = "a",
        var22aA.value = "42",
        var22aA.type = "int",
        var22aA.instanceId = "object-a"

        MERGE (var22bB:Variable {variableKey: $repoName + "-debug-run-2-snapshot-2-var-b-object-b"})
        SET var22bB.name = "b",
        var22bB.value = "999",
        var22bB.type = "int",
        var22bB.instanceId = "object-b"

        MERGE (var22cC:Variable {variableKey: $repoName + "-debug-run-2-snapshot-2-var-c-object-c"})
        SET var22cC.name = "c",
        var22cC.value = "true",
        var22cC.type = "boolean",
        var22cC.instanceId = "object-c"

        MERGE (var22dH:Variable {variableKey: $repoName + "-debug-run-2-snapshot-2-var-d-object-h"})
        SET var22dH.name = "d",
        var22dH.value = "256.75",
        var22dH.type = "double",
        var22dH.instanceId = "object-h"

        MERGE (var22eI:Variable {variableKey: $repoName + "-debug-run-2-snapshot-2-var-e-object-i"})
        SET var22eI.name = "e",
        var22eI.value = "OPEN",
        var22eI.type = "string",
        var22eI.instanceId = "object-i"

        /* debug-run-3, snapshot-1 */
        MERGE (var31aA:Variable {variableKey: $repoName + "-debug-run-3-snapshot-1-var-a-object-a"})
        SET var31aA.name = "a",
        var31aA.value = "42",
        var31aA.type = "int",
        var31aA.instanceId = "object-a"

        MERGE (var31bJ:Variable {variableKey: $repoName + "-debug-run-3-snapshot-1-var-b-object-j"})
        SET var31bJ.name = "b",
        var31bJ.value = "0.0",
        var31bJ.type = "float",
        var31bJ.instanceId = "object-j"

        MERGE (var31cK:Variable {variableKey: $repoName + "-debug-run-3-snapshot-1-var-c-object-k"})
        SET var31cK.name = "c",
        var31cK.value = "abc",
        var31cK.type = "string",
        var31cK.instanceId = "object-k"

        /* debug-run-3, snapshot-2 */
        MERGE (var32aA:Variable {variableKey: $repoName + "-debug-run-3-snapshot-2-var-a-object-a"})
        SET var32aA.name = "a",
        var32aA.value = "42",
        var32aA.type = "int",
        var32aA.instanceId = "object-a"

        MERGE (var32bJ:Variable {variableKey: $repoName + "-debug-run-3-snapshot-2-var-b-object-j"})
        SET var32bJ.name = "b",
        var32bJ.value = "1.5",
        var32bJ.type = "float",
        var32bJ.instanceId = "object-j"

        MERGE (var32cK:Variable {variableKey: $repoName + "-debug-run-3-snapshot-2-var-c-object-k"})
        SET var32cK.name = "c",
        var32cK.value = "def",
        var32cK.type = "string",
        var32cK.instanceId = "object-k"

        MERGE (var32dL:Variable {variableKey: $repoName + "-debug-run-3-snapshot-2-var-d-object-l"})
        SET var32dL.name = "d",
        var32dL.value = "false",
        var32dL.type = "boolean",
        var32dL.instanceId = "object-l"

        /* debug-run-3, snapshot-3 */
        MERGE (var33aA:Variable {variableKey: $repoName + "-debug-run-3-snapshot-3-var-a-object-a"})
        SET var33aA.name = "a",
        var33aA.value = "42",
        var33aA.type = "int",
        var33aA.instanceId = "object-a"

        MERGE (var33bJ:Variable {variableKey: $repoName + "-debug-run-3-snapshot-3-var-b-object-j"})
        SET var33bJ.name = "b",
        var33bJ.value = "2.75",
        var33bJ.type = "double",
        var33bJ.instanceId = "object-j"

        MERGE (var33cL:Variable {variableKey: $repoName + "-debug-run-3-snapshot-3-var-c-object-l"})
        SET var33cL.name = "c",
        var33cL.value = "true",
        var33cL.type = "boolean",
        var33cL.instanceId = "object-l"

        MERGE (var33dM:Variable {variableKey: $repoName + "-debug-run-3-snapshot-3-var-d-object-m"})
        SET var33dM.name = "d",
        var33dM.value = "done",
        var33dM.type = "string",
        var33dM.instanceId = "object-m"

        /* captures */
        MERGE (ds11)-[:CAPTURES]->(var11aA)
        MERGE (ds11)-[:CAPTURES]->(var11aB)
        MERGE (ds11)-[:CAPTURES]->(var11bD)
        MERGE (ds11)-[:CAPTURES]->(var11cE)

        MERGE (ds12)-[:CAPTURES]->(var12aA)
        MERGE (ds12)-[:CAPTURES]->(var12bA)
        MERGE (ds12)-[:CAPTURES]->(var12cD)
        MERGE (ds12)-[:CAPTURES]->(var12dF)

        MERGE (ds13)-[:CAPTURES]->(var13aA)
        MERGE (ds13)-[:CAPTURES]->(var13bA)
        MERGE (ds13)-[:CAPTURES]->(var13cD)
        MERGE (ds13)-[:CAPTURES]->(var13dG)

        MERGE (ds21)-[:CAPTURES]->(var21aA)
        MERGE (ds21)-[:CAPTURES]->(var21bA)
        MERGE (ds21)-[:CAPTURES]->(var21cB)
        MERGE (ds21)-[:CAPTURES]->(var21dH)

        MERGE (ds22)-[:CAPTURES]->(var22aA)
        MERGE (ds22)-[:CAPTURES]->(var22bB)
        MERGE (ds22)-[:CAPTURES]->(var22cC)
        MERGE (ds22)-[:CAPTURES]->(var22dH)
        MERGE (ds22)-[:CAPTURES]->(var22eI)

        MERGE (ds31)-[:CAPTURES]->(var31aA)
        MERGE (ds31)-[:CAPTURES]->(var31bJ)
        MERGE (ds31)-[:CAPTURES]->(var31cK)

        MERGE (ds32)-[:CAPTURES]->(var32aA)
        MERGE (ds32)-[:CAPTURES]->(var32bJ)
        MERGE (ds32)-[:CAPTURES]->(var32cK)
        MERGE (ds32)-[:CAPTURES]->(var32dL)

        MERGE (ds33)-[:CAPTURES]->(var33aA)
        MERGE (ds33)-[:CAPTURES]->(var33bJ)
        MERGE (ds33)-[:CAPTURES]->(var33cL)
        MERGE (ds33)-[:CAPTURES]->(var33dM)

        /* marked in */
        MERGE (var11aA)-[:MARKED_IN]->(file1)
        MERGE (var11aB)-[:MARKED_IN]->(file1)
        MERGE (var11bD)-[:MARKED_IN]->(file4)
        MERGE (var11cE)-[:MARKED_IN]->(file7)

        MERGE (var12aA)-[:MARKED_IN]->(file1)
        MERGE (var12bA)-[:MARKED_IN]->(file1)
        MERGE (var12cD)-[:MARKED_IN]->(file4)
        MERGE (var12dF)-[:MARKED_IN]->(file5)

        MERGE (var13aA)-[:MARKED_IN]->(file1)
        MERGE (var13bA)-[:MARKED_IN]->(file1)
        MERGE (var13cD)-[:MARKED_IN]->(file4)
        MERGE (var13dG)-[:MARKED_IN]->(file8)

        MERGE (var21aA)-[:MARKED_IN]->(file1)
        MERGE (var21bA)-[:MARKED_IN]->(file1)
        MERGE (var21cB)-[:MARKED_IN]->(file2)
        MERGE (var21dH)-[:MARKED_IN]->(file7)

        MERGE (var22aA)-[:MARKED_IN]->(file1)
        MERGE (var22bB)-[:MARKED_IN]->(file2)
        MERGE (var22cC)-[:MARKED_IN]->(file3)
        MERGE (var22dH)-[:MARKED_IN]->(file7)
        MERGE (var22eI)-[:MARKED_IN]->(file8)

        MERGE (var31aA)-[:MARKED_IN]->(file1)
        MERGE (var31bJ)-[:MARKED_IN]->(file4)
        MERGE (var31cK)-[:MARKED_IN]->(file8)

        MERGE (var32aA)-[:MARKED_IN]->(file1)
        MERGE (var32bJ)-[:MARKED_IN]->(file4)
        MERGE (var32cK)-[:MARKED_IN]->(file8)
        MERGE (var32dL)-[:MARKED_IN]->(file8)

        MERGE (var33aA)-[:MARKED_IN]->(file1)
        MERGE (var33bJ)-[:MARKED_IN]->(file4)
        MERGE (var33cL)-[:MARKED_IN]->(file8)
        MERGE (var33dM)-[:MARKED_IN]->(file8)
        """,
        Map.of("repoName", repoName));
    final Result variableResult =
        session.query(
            """
            MATCH (:Landscape {tokenId: "mytokenvalue"})
              -[:CONTAINS]->(:Repository {name: $repositoryName})
              -[:HAS_DEBUG_RUN]->(debugRun:DebugRun)
              -[:CONTAINS]->(snapshot:DebugSnapshot)
              -[:CAPTURES]->(variable:Variable)
            RETURN
              debugRun.debugRunKey AS debugRunKey,
              snapshot.debugSnapshotKey AS debugSnapshotKey,
              snapshot.timestamp AS snapshotTimestamp,
              collect(variable) AS variables
            ORDER BY debugRun.debugRunKey, snapshot.timestamp
            """,
            Map.of("repositoryName", repoName));
    final Map<String, Map<String, Double>> mutationCountsByDebugRun = new HashMap<>();

    variableResult
        .queryResults()
        .forEach(
            row -> {
              final String debugRunKey = (String) row.get("debugRunKey");
              final List<Variable> variables = (List<Variable>) row.get("variables");

              final Map<String, Double> mutationCounts =
                  mutationCountsByDebugRun.computeIfAbsent(debugRunKey, ignored -> new HashMap<>());

              variables.forEach(
                  variable -> {
                    final String variableIdentity =
                        variable.getInstanceId() + "::" + variable.getName();

                    final double previousMutationCount =
                        mutationCounts.getOrDefault(variableIdentity, 0.0);

                    final double nextMutationCount =
                        previousMutationCount + (int) Math.floor(Math.random() * 10);

                    addVariableMutationCountMetric(variable, nextMutationCount);
                    mutationCounts.put(variableIdentity, nextMutationCount);

                    session.save(variable);
                  });
            });
    return "Successfully created example \"debug\"";
  }

  @GET
  @Path("/trace")
  public String createTestingDynamicData() {
    final Session session = sessionFactory.openSession();
    session.query(
        """
        MERGE (l:Landscape {tokenId: "mytokenvalue"})
        MERGE (l)-[:CONTAINS]->(t1:Trace {traceId: "trace1"})
        MERGE (l)-[:CONTAINS]->(t2:Trace {traceId: "trace2"})
        SET t1.startTime = 1000000000, t1.endTime = 1001000000
        SET t2.startTime = 2000000000, t2.endTime = 4002800000
        MERGE (t1)-[:CONTAINS]->(s1:Span {spanId: "span1"})
        MERGE (t2)-[:CONTAINS]->(s2:Span {spanId: "span2"})
        MERGE (t2)-[:CONTAINS]->(s3:Span {spanId: "span3"})-[:HAS_PARENT]->(s2)
        MERGE (t2)-[:CONTAINS]->(s4:Span {spanId: "span4"})-[:HAS_PARENT]->(s3)

        SET s1.startTime = 1000000000, s1.endTime = 1001000000
        SET s2.startTime = 2000000000, s2.endTime = 2003000000
        SET s3.startTime = 2500000000, s3.endTime = 3002900000
        SET s4.startTime = 3000000000, s4.endTime = 4002800000

        MERGE (l)-[:CONTAINS]->(app:Application {name: "hello-world"})
        MERGE (app)-[:HAS_ROOT]->(appRoot:Directory {name: "hello-world"})

        MERGE (appRoot)-[:CONTAINS]->(d1:Directory {name: "net"})
        MERGE (d1)-[:CONTAINS]->(d2:Directory {name: "explorviz"})
        MERGE (d2)-[:CONTAINS]->(outerDir:Directory {name: "helloworld"})
        MERGE (outerDir)-[:CONTAINS]->(innerDir:Directory {name: "innerpackage"})
        MERGE (outerDir)-[:CONTAINS]->(file1:FileRevision {name: "File1.java"})
        MERGE (outerDir)-[:CONTAINS]->(file2:FileRevision {name: "File2.java"})
        MERGE (innerDir)-[:CONTAINS]->(file3:FileRevision {name: "File3.java"})
        MERGE (file1)-[:CONTAINS]->(func1:Function {name: "function1"})
        MERGE (file2)-[:CONTAINS]->(func2:Function {name: "function2"})
        MERGE (file3)-[:CONTAINS]->(func3:Function {name: "function3"})

        MERGE (l)-[:CONTAINS]->(r1:Repository {name: "hello-world"})
        MERGE (r1)-[:CONTAINS]->(c1:Commit {hash: "commit1"})
        MERGE (c1)-[:CONTAINS]->(file1)
        MERGE (r1)-[:HAS_ROOT]->(appRoot)

        MERGE (s1)-[:REPRESENTS]->(func1)
        MERGE (s2)-[:REPRESENTS]->(func2)
        MERGE (s3)-[:REPRESENTS]->(func3)<-[:REPRESENTS]-(s4);
        """,
        Map.of());
    return "Successfully created example \"trace\"";
  }

  @GET
  @Path("/repo")
  @SuppressWarnings("unchecked")
  public String createTestingRepository(@RestQuery final String name) {
    final String repoName = name != null ? name : "hello-world";

    final Session session = sessionFactory.openSession();
    final Result result =
        session.query(
            """
            MERGE (l:Landscape {tokenId: "mytokenvalue"})
            MERGE (l)-[:CONTAINS]->(repo:Repository {name: $repoName})
            MERGE (repo)-[:CONTAINS]->(main:Branch {name: "main"})
            MERGE (repo)-[:CONTAINS]->(feature: Branch {name: "feature-a"})

            MERGE (repo)-[:CONTAINS]->(commit1:Commit {hash: "commit1", authorDate: 1000})
            MERGE (commit1)-[:BELONGS_TO]->(main)
            MERGE (repo)-[:CONTAINS]->(commit2:Commit {hash: "commit2", authorDate: 2000})
            MERGE (commit2)-[:BELONGS_TO]->(main)
            MERGE (commit2)-[:HAS_PARENT]->(commit1)
            MERGE (repo)-[:CONTAINS]->(commit3:Commit {hash: "commit3", authorDate: 3000})
            MERGE (commit3)-[:BELONGS_TO]->(feature)
            MERGE (commit3)-[:HAS_PARENT]->(commit2)

            MERGE (l)-[:CONTAINS]->(app:Application {name: $repoName})
            MERGE (app)-[:HAS_ROOT]->(rootDir:Directory {name: $repoName})
            MERGE (rootDir)-[:CONTAINS]->(d1:Directory {name: "net"})
            MERGE (d1)-[:CONTAINS]->(d2:Directory {name: "explorviz"})
            MERGE (d2)-[:CONTAINS]->(outerDir:Directory {name: "persistence"})
            MERGE (outerDir)-[:CONTAINS]->(innerDir:Directory {name: "innerpackage"})

            MERGE (outerDir)-[:CONTAINS]->(file1:FileRevision {name: "ClassA.java"})
            MERGE (file1)-[:CONTAINS]->(class1:Clazz {name: "ClassA"})
            MERGE (outerDir)-[:CONTAINS]->(file2:FileRevision {name: "ClassB.java"})
            MERGE (file2)-[:CONTAINS]->(class2:Clazz {name: "ClassB"})
            MERGE (outerDir)-[:CONTAINS]->(file2modified:FileRevision {name: "ClassB.java"})
            MERGE (file2modified)-[:CONTAINS]->(class2modified:Clazz {name: "ClassB"})
            MERGE (innerDir)-[:CONTAINS]->(file3:FileRevision {name: "ClassC.java"})
            MERGE (file3)-[:CONTAINS]->(class3:Clazz {name: "ClassC"})

            MERGE (repo)-[:HAS_ROOT]->(rootDir)
            MERGE (commit1)-[:CONTAINS]->(file1)
            MERGE (commit2)-[:CONTAINS]->(file1)
            MERGE (commit2)-[:CONTAINS]->(file2)
            MERGE (commit3)-[:CONTAINS]->(file2modified)
            MERGE (commit3)-[:CONTAINS]->(file3)

            RETURN
              [file1, file2, file2modified, file3] AS files,
              [class1, class2, class2modified, class3] AS classes;
            """,
            Map.of("repoName", repoName));

    result
        .queryResults()
        .forEach(
            qr -> {
              final List<FileRevision> files = (List<FileRevision>) qr.get("files");
              final List<Clazz> classes = (List<Clazz>) qr.get("classes");

              files.forEach(
                  f -> {
                    addFunctionsToFile(f);
                    addRandomFileMetrics(f);
                    session.save(f);
                  });

              classes.forEach(
                  c -> {
                    addFunctionsToClass(c);
                    addRandomClassMetrics(c);
                    session.save(c);
                  });
            });

    return "Successfully created example \"repo\"";
  }

  @SuppressWarnings("unchecked")
  public void createTestingRepositoryDifferentBranchPoint(final String name) {
    final String repoName = name != null ? name : "hello-underworld";

    final Session session = sessionFactory.openSession();
    final Result result =
        session.query(
            """
            MERGE (l:Landscape {tokenId: "mytokenvalue"})
            MERGE (l)-[:CONTAINS]->(repo:Repository {name: $repoName})
            MERGE (repo)-[:CONTAINS]->(main:Branch {name: "main"})
            MERGE (repo)-[:CONTAINS]->(feature: Branch {name: "feature-a"})

            MERGE (repo)-[:CONTAINS]->(commit1:Commit {hash: "commit1", authorDate: 1000})
            MERGE (commit1)-[:BELONGS_TO]->(main)
            MERGE (repo)-[:CONTAINS]->(commit2:Commit {hash: "commit2", authorDate: 2000})
            MERGE (commit2)-[:BELONGS_TO]->(feature)
            MERGE (commit2)-[:HAS_PARENT]->(commit1)
            MERGE (repo)-[:CONTAINS]->(commit3:Commit {hash: "commit3", authorDate: 3000})
            MERGE (commit3)-[:BELONGS_TO]->(main)
            MERGE (commit3)-[:HAS_PARENT]->(commit1)

            MERGE (l)-[:CONTAINS]->(app:Application {name: $repoName})
            MERGE (app)-[:HAS_ROOT]->(rootDir:Directory {name: $repoName})
            MERGE (rootDir)-[:CONTAINS]->(d1:Directory {name: "net"})
            MERGE (d1)-[:CONTAINS]->(d2:Directory {name: "explorviz"})
            MERGE (d2)-[:CONTAINS]->(outerDir:Directory {name: "persistence"})
            MERGE (outerDir)-[:CONTAINS]->(innerDir:Directory {name: "innerpackage"})

            MERGE (outerDir)-[:CONTAINS]->(file1:FileRevision {name: "ClassA.java"})
            MERGE (file1)-[:CONTAINS]->(class1:Clazz {name: "ClassA"})
            MERGE (outerDir)-[:CONTAINS]->(file2:FileRevision {name: "ClassB.java"})
            MERGE (file2)-[:CONTAINS]->(class2:Clazz {name: "ClassB"})
            MERGE (outerDir)-[:CONTAINS]->(file2modified:FileRevision {name: "ClassB.java"})
            MERGE (file2modified)-[:CONTAINS]->(class2modified:Clazz {name: "ClassB"})
            MERGE (innerDir)-[:CONTAINS]->(file3:FileRevision {name: "ClassC.java"})
            MERGE (file3)-[:CONTAINS]->(class3:Clazz {name: "ClassC"})

            MERGE (repo)-[:HAS_ROOT]->(rootDir)
            MERGE (commit1)-[:CONTAINS]->(file1)
            MERGE (commit2)-[:CONTAINS]->(file1)
            MERGE (commit2)-[:CONTAINS]->(file2)
            MERGE (commit3)-[:CONTAINS]->(file2modified)
            MERGE (commit3)-[:CONTAINS]->(file3)

            RETURN
              [file1, file2, file2modified, file3] AS files,
              [class1, class2, class2modified, class3] AS classes;
            """,
            Map.of("repoName", repoName));

    result
        .queryResults()
        .forEach(
            qr -> {
              final List<FileRevision> files = (List<FileRevision>) qr.get("files");
              final List<Clazz> classes = (List<Clazz>) qr.get("classes");

              files.forEach(
                  f -> {
                    addFunctionsToFile(f);
                    addRandomFileMetrics(f);
                    session.save(f);
                  });

              classes.forEach(
                  c -> {
                    addFunctionsToClass(c);
                    addRandomClassMetrics(c);
                    session.save(c);
                  });
            });
  }

  @GET
  @Path("/monorepo")
  public String createTestingMonorepo() {
    final Landscape landscape = new Landscape("mytokenvalue");

    final Repository repository = new Repository("monorepo");
    landscape.addRepository(repository);

    final Branch branch1 = new Branch("main");
    final Branch branch2 = new Branch("feature-a");
    repository.addBranch(branch1);
    repository.addBranch(branch2);

    final Commit commit1 = new Commit("commit1");
    final Commit commit2 = new Commit("commit2");
    final Commit commit3 = new Commit("commit3");
    commit1.setBranch(branch1);
    commit1.setAuthorDate(Instant.ofEpochMilli(1000));
    commit2.setBranch(branch1);
    commit2.addParentCommit(commit1);
    commit2.setAuthorDate(Instant.ofEpochMilli(1000));
    commit3.setBranch(branch2);
    commit3.addParentCommit(commit1);
    commit1.setAuthorDate(Instant.ofEpochMilli(1500));
    repository.addCommit(commit1);
    repository.addCommit(commit2);
    repository.addCommit(commit3);

    final Application application1 = new Application("app-one");
    final Application application2 = new Application("app-two");
    landscape.addApplication(application1);
    landscape.addApplication(application2);

    final Directory repoRoot = new Directory("monorepo");
    repository.setRootDirectory(repoRoot);
    repoRoot.addFileRevision(new FileRevision("README.md"));

    Directory appOneDir = new Directory("app-one");
    Directory appTwoDir = new Directory("app-two");

    application1.setRootDirectory(appOneDir);
    application2.setRootDirectory(appTwoDir);

    repoRoot.addSubdirectory(appOneDir);
    repoRoot.addSubdirectory(appTwoDir);

    final String[] appOneDirNames = {"net", "explorviz", "appone"};
    final String[] appTwoDirNames = {"net", "explorviz", "apptwo"};

    for (final String dirName : appOneDirNames) {
      final Directory newDir = new Directory(dirName);
      appOneDir.addSubdirectory(newDir);
      appOneDir = newDir;
    }

    for (final String dirName : appTwoDirNames) {
      final Directory newDir = new Directory(dirName);
      appTwoDir.addSubdirectory(newDir);
      appTwoDir = newDir;
    }

    final FileRevision fileA1 = new FileRevision("ClassA.java");
    final FileRevision fileB1 = new FileRevision("ClassB.java");
    final FileRevision fileB1Modified = new FileRevision("ClassB.java");
    final FileRevision fileA2 = new FileRevision("ClassA.java");
    final FileRevision fileB2 = new FileRevision("ClassB.java");
    appOneDir.addFileRevision(fileA1);
    appOneDir.addFileRevision(fileB1);
    appOneDir.addFileRevision(fileB1Modified);
    appTwoDir.addFileRevision(fileA2);
    appTwoDir.addFileRevision(fileB2);
    commit1.addFileRevision(fileA1);
    commit1.addFileRevision(fileA2);
    commit1.addFileRevision(fileB1);
    commit1.addFileRevision(fileB2);
    commit2.addFileRevision(fileA1);
    commit2.addFileRevision(fileA2);
    commit3.addFileRevision(fileA1);
    commit3.addFileRevision(fileB1Modified);
    commit3.addFileRevision(fileA2);
    commit3.addFileRevision(fileB2);
    List.of(fileA1, fileA2, fileB1, fileB2)
        .forEach(
            f -> {
              addFunctionsToFile(f);
              addRandomFileMetrics(f);
            });

    final Session session = sessionFactory.openSession();
    session.save(List.of(landscape, application1, application2));

    return "Successfully created example \"monorepo\"";
  }

  /** Code-agent analysis of spring-petclinic repository, limited to the two latest commits. */
  @GET
  @Path("/petclinic-static")
  public String createPetclinicStatic() {
    final String resourceFilePath = "example-data/petclinic-static.cypher";
    executeCypherFile(resourceFilePath);
    return "Successfully created example \"petclinic-static\"";
  }

  /** Trace-generator result using the "petclinic" preset. */
  @GET
  @Path("/petclinic-runtime")
  public String createPetclinicRuntime() {
    final String resourceFilePath = "example-data/petclinic-runtime.cypher";
    executeCypherFile(resourceFilePath);
    return "Successfully created example \"petclinic-runtime\"";
  }

  /** Combined result of code-agent and trace-generator for spring-petclinic. */
  @GET
  @Path("/petclinic")
  public String createPetclinicCombined() {
    final String resourceFilePath = "example-data/petclinic-combined.cypher";
    executeCypherFile(resourceFilePath);
    return "Successfully created example \"petclinic\"";
  }

  @GET
  @Path("/purge")
  public String purgeDatabase() {
    final Session session = sessionFactory.openSession();
    session.purgeDatabase();
    return "Database purge successful";
  }

  private void addFunctionsToFile(final FileRevision fileRevision) {
    fileRevision.addFunction(new Function("doSomething"));
    fileRevision.addFunction(new Function("findObject"));
    fileRevision.addFunction(new Function("tryMyBest"));
  }

  private void addFunctionsToClass(final Clazz clazz) {
    clazz.addFunction(new Function("performClassMethod"));
    clazz.addFunction(new Function("encapsulateParent"));
    clazz.addFunction(new Function("inheritInterface"));
  }

  private void addRandomFileMetrics(final FileRevision fileRevision) {
    fileRevision.setMetrics(
        Map.ofEntries(
            Map.entry("LCOM4", Math.floor(Math.random() * 5)),
            Map.entry("loc", Math.floor(Math.random() * 250)),
            Map.entry("cyclomatic_complexity", Math.floor(Math.random() * 10)),
            Map.entry("cyclomatic_complexity_weighted", Math.floor(Math.random() * 10))));
  }

  private void addRandomClassMetrics(final Clazz clazz) {
    clazz.setMetrics(
        Map.ofEntries(
            Map.entry("LCOM4", Math.floor(Math.random() * 5)),
            Map.entry("loc", Math.floor(Math.random() * 250)),
            Map.entry("cyclomatic_complexity", Math.floor(Math.random() * 10)),
            Map.entry("cycolomatic_complexity_weighted", Math.floor(Math.random() * 10))));
  }

  private void addVariableMutationCountMetric(final Variable variable, final double mutationCount) {
    variable.setMetrics(Map.ofEntries(Map.entry("mutationCount", mutationCount)));
  }

  /**
   * Executes all Cypher statements in the given file. Each statement is expected to be separated by
   * semicolon. Lines starting with // and empty lines are ignored.
   */
  @SuppressWarnings("PMD.CloseResource") // This is handled by the BufferedReader
  private void executeCypherFile(
      final String resourceFilePath, final Map<String, Object> parameters) {
    final InputStream fileInputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourceFilePath);
    if (fileInputStream == null) {
      throw new InternalServerErrorException(
          "Requested resource file could not be found: " + resourceFilePath);
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(fileInputStream, StandardCharsets.UTF_8))) {
      final String[] cypherStatements =
          reader
              .lines()
              .filter(l -> !l.startsWith("//") && !l.isBlank())
              .collect(Collectors.joining(" "))
              .split(";");
      reader.close();
      final Session session = sessionFactory.openSession();
      session.purgeDatabase();
      Arrays.stream(cypherStatements)
          .map(String::trim)
          .filter(s -> !s.isBlank())
          .forEach(s -> session.query(s, parameters));
    } catch (final IOException e) {
      throw new InternalServerErrorException(
          "Failed to load example cypher file: " + e.getMessage(), e);
    }
  }

  private void executeCypherFile(final String resourceFilePath) {
    executeCypherFile(resourceFilePath, Map.of());
  }

  private void addRandomSpan(final Trace trace, final String name) {
    final Span span = new Span(name);
    final long randNumb = (long) (Math.random() * 100_000_000_000.0);
    span.setStartTime(randNumb);
    span.setEndTime(randNumb + 1);
    trace.addSpan(span);
  }

  @GET
  @Path("/timestamp")
  public String createTestingTimestamps() {
    final Landscape landscape = new Landscape("mytokenvalue");

    final Trace trace1 = new Trace("trace1");
    final Trace trace2 = new Trace("trace2");

    for (int i = 0; i < 5; i++) {
      addRandomSpan(trace1, "trace1_span" + i);
      addRandomSpan(trace2, "trace2_span" + i);
    }

    landscape.addTrace(trace1);
    landscape.addTrace(trace2);

    final Session session = sessionFactory.openSession();
    session.save(List.of(landscape));

    return "Successfully created testing timestamps";
  }

  @GET
  @Path("/multirepo")
  public String createTestingMultiRepo() {
    createTestingRepository("hello-world");
    createTestingRepositoryDifferentBranchPoint("hello-underworld");
    return "Successfully created example \"multirepo\"";
  }
}
