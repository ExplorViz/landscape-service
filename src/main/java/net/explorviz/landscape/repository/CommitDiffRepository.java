package net.explorviz.landscape.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import net.explorviz.landscape.ogm.FileRevision;
import org.neo4j.ogm.session.Session;

@ApplicationScoped
public class CommitDiffRepository {

  public record CommitDiffQuery(
      String landscapeToken,
      String applicationName,
      String firstCommitHash,
      String secondCommitHash) {}

  /**
   * Returns the FQNs of all files within the provided application which are present in both the
   * first commit and the second commit, but not under the same FileRevision node, indicating that
   * there has been a change to the file.
   */
  public List<String> findModifiedFileFqns(final Session session, final CommitDiffQuery query) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(repo:Repository)
              -[:CONTAINS]->(c1:Commit {hash: $firstCommitHash})
            MATCH (repo)-[:CONTAINS]->(c2:Commit {hash: $secondCommitHash})
            MATCH (l)-[:CONTAINS]->(a:Application {name: $appName})
            WHERE
              (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
            MATCH p = (a)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS]->*(containingDir:Directory)
              -[:CONTAINS]->(f:FileRevision)
            WHERE
              (c1)-[:CONTAINS]->(f) AND
              NOT (c2)-[:CONTAINS]->(f) AND
              EXISTS {
                MATCH (containingDir)-[:CONTAINS]->(f2:FileRevision)<-[:CONTAINS]-(c2)
                WHERE f.name = f2.name AND f <> f2
              }
            RETURN apoc.text.join([node IN nodes(p)[1..] | node.name], "/");
            """,
            diffQueryParams(query)));
  }

  /**
   * Returns the FQNs of all files within the provided application which are present in the second
   * commit, but not the first commit, indicating they are added in the second commit.
   */
  public List<String> findAddedFileFqns(final Session session, final CommitDiffQuery query) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(repo:Repository)
              -[:CONTAINS]->(c1:Commit {hash: $firstCommitHash})
            MATCH (repo)-[:CONTAINS]->(c2:Commit {hash: $secondCommitHash})
            MATCH (l)-[:CONTAINS]->(a:Application {name: $appName})
            WHERE
              (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
            MATCH p = (a)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS]->*(containingDir:Directory)
              -[:CONTAINS]->(f:FileRevision)
            WHERE
              (c2)-[:CONTAINS]->(f) AND
              NOT (c1)-[:CONTAINS]->(f) AND
              NOT EXISTS {
                MATCH (containingDir)-[:CONTAINS]->(f2:FileRevision)<-[:CONTAINS]-(c1)
                WHERE f.name = f2.name AND f <> f2
              }
            RETURN apoc.text.join([node IN nodes(p)[1..] | node.name], "/");
            """,
            diffQueryParams(query)));
  }

  /**
   * Returns the FQNs of all files within the provided application which are present in the first
   * commit, but not the second commit, indicating they are deleted in the second commit.
   */
  public List<String> findDeletedFileFqns(final Session session, final CommitDiffQuery query) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(repo:Repository)
              -[:CONTAINS]->(c1:Commit {hash: $firstCommitHash})
            MATCH (repo)-[:CONTAINS]->(c2:Commit {hash: $secondCommitHash})
            MATCH (l)-[:CONTAINS]->(a:Application {name: $appName})
            WHERE
              (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
            MATCH p = (a)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS]->*(containingDir:Directory)
              -[:CONTAINS]->(f:FileRevision)
            WHERE
              (c1)-[:CONTAINS]->(f) AND
              NOT (c2)-[:CONTAINS]->(f) AND
              NOT EXISTS {
                MATCH (containingDir)-[:CONTAINS]->(f2:FileRevision)<-[:CONTAINS]-(c2)
                WHERE f.name = f2.name AND f <> f2
              }
            RETURN apoc.text.join([node IN nodes(p)[1..] | node.name], "/");
            """,
            diffQueryParams(query)));
  }

  /**
   * Returns the FQNs of all directories within the provided application which contain files that
   * are present in the second commit, but no files present in the first commit, indicating these
   * directories are added in the second commit.
   */
  public List<String> findAddedDirectoryFqns(final Session session, final CommitDiffQuery query) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(repo:Repository)
              -[:CONTAINS]->(c1:Commit {hash: $firstCommitHash})
            MATCH (repo)-[:CONTAINS]->(c2:Commit {hash: $secondCommitHash})
            MATCH (l)-[:CONTAINS]->(a:Application {name: $appName})
            WHERE
              (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
            MATCH p = (a)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS*0..]->(d:Directory)
            WHERE
              (d)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c2) AND
              NOT (d)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
            RETURN apoc.text.join([node IN nodes(p)[1..] | node.name], "/");
            """,
            diffQueryParams(query)));
  }

  /**
   * Returns the FQNs of all directories within the provided application which contain files that
   * are present in the first commit, but no files present in the second commit, indicating these
   * directories are deleted in the second commit.
   */
  public List<String> findDeletedDirectoryFqns(final Session session, final CommitDiffQuery query) {
    return Lists.newArrayList(
        session.query(
            String.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(repo:Repository)
              -[:CONTAINS]->(c1:Commit {hash: $firstCommitHash})
            MATCH (repo)-[:CONTAINS]->(c2:Commit {hash: $secondCommitHash})
            MATCH (l)-[:CONTAINS]->(a:Application {name: $appName})
            WHERE
              (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
            MATCH p = (a)
              -[:HAS_ROOT]->(:Directory)
              -[:CONTAINS*0..]->(d:Directory)
            WHERE
              (d)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1) AND
              NOT (d)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c2)
            RETURN apoc.text.join([node IN nodes(p)[1..] | node.name], "/");
            """,
            diffQueryParams(query)));
  }

  /**
   * Finds all file revisions which are part of the provided application and are contained in the
   * first commit, along with the file's FQNs. Additionally, if a file revision with the same FQN is
   * present in the second commit, it is also returned, allowing the comparison of file attributes.
   * Note that if the exact same file revision is part of both commits, it is returned twice.
   */
  public List<FileComparison> findFilesWithCounterpart(
      final Session session, final CommitDiffQuery query) {
    return session.queryDto(
        """
        MATCH (l:Landscape {tokenId: $tokenId})
          -[:CONTAINS]->(repo:Repository)
          -[:CONTAINS]->(c1:Commit {hash: $firstCommitHash})
        MATCH (repo)-[:CONTAINS]->(c2:Commit {hash: $secondCommitHash})
        MATCH (l)-[:CONTAINS]->(a:Application {name: $appName})
        WHERE
          (a)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*]->(:FileRevision)<-[:CONTAINS]-(c1)
        MATCH p = (a)
          -[:HAS_ROOT]->(:Directory)
          -[:CONTAINS]->*(containingDir:Directory)
          -[:CONTAINS]->(f:FileRevision)
        WHERE
          (c2)-[:CONTAINS]->(f)
        OPTIONAL MATCH (containingDir)-[:CONTAINS]->(f2:FileRevision)<-[:CONTAINS]-(c1)
        WHERE f.name = f2.name
        RETURN
          apoc.text.join([node IN nodes(p)[1..] | node.name], "/") AS fileFqn,
          f AS fileFirstCommit,
          f2 AS fileSecondCommit;
        """,
        diffQueryParams(query),
        FileComparison.class);
  }

  private static Map<String, Object> diffQueryParams(final CommitDiffQuery query) {
    return Map.of(
        "tokenId", query.landscapeToken(),
        "appName", query.applicationName(),
        "firstCommitHash", query.firstCommitHash(),
        "secondCommitHash", query.secondCommitHash());
  }

  public record FileComparison(
      String fileFqn, FileRevision fileFirstCommit, FileRevision fileSecondCommit) {}
}
