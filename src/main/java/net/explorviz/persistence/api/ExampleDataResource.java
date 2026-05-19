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

  /**
   * Executes all Cypher statements in the given file. Each statement is expected to be separated by
   * semicolon. Lines starting with // and empty lines are ignored.
   */
  @SuppressWarnings("PMD.CloseResource") // This is handled by the BufferedReader
  private void executeCypherFile(final String resourceFilePath) {
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
      Arrays.stream(cypherStatements).forEach(s -> session.query(s, Map.of()));
    } catch (final IOException e) {
      throw new InternalServerErrorException(
          "Failed to load example cypher file: " + e.getMessage(), e);
    }
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
