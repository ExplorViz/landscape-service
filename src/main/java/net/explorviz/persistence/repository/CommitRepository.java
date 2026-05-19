package net.explorviz.persistence.repository;

import com.google.common.collect.Lists;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.FileRevision;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@ApplicationScoped
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseObjectForClearerAPI"})
public class CommitRepository {

  @Inject SessionFactory sessionFactory;

  /**
   * Find latest commit for which we have seen a CommitData message and also a FileData message for
   * every file included in the commit.
   */
  public Optional<Commit> findLatestFullyPersistedCommit(
      final Session session,
      final String repoName,
      final String tokenId,
      final String branchName,
      final List<String> applicationNames) {
    return Optional.ofNullable(
        session.queryForObject(
            Commit.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})
            MATCH (repo:Repository {name: $repoName})<-[:CONTAINS]-(l)
            MATCH (repo)-[:CONTAINS]->(c:Commit)-[:BELONGS_TO]->(:Branch {name: $branchName})
            WHERE all(appName IN $applicationNames WHERE EXISTS {
              MATCH (l)-[:CONTAINS]->(a:Application {name: appName})-[:HAS_ROOT]->(root:Directory)
              MATCH (root)-[:CONTAINS*1..]->(f:FileRevision)<-[:CONTAINS]-(c)
            })
            WITH c, [(c)-[:CONTAINS]->(f:FileRevision) | f] AS filesInCommit
            WHERE
              all(file IN filesInCommit WHERE file.hasFileData) AND
              NOT isEmpty(filesInCommit)
            RETURN c
            ORDER BY c.commitDate DESC
            LIMIT 1;
            """,
            Map.of(
                "tokenId",
                tokenId,
                "repoName",
                repoName,
                "branchName",
                branchName,
                "applicationNames",
                applicationNames)));
  }

  public Optional<Commit> findCommitByHashAndLandscapeToken(
      final Session session, final String commitHash, final String tokenId) {
    return Optional.ofNullable(
        session.queryForObject(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})
              -[:CONTAINS]->(:Repository)
              -[:CONTAINS]->(c:Commit {hash: $commitHash})
            RETURN c;
            """,
            Map.of("tokenId", tokenId, "commitHash", commitHash)));
  }

  /**
   * Returns every commit (along with its branch and parent relations) in the specified repository,
   * ordered by author date (ascending, meaning oldest first).
   */
  public List<Commit> findCommitsWithBranchForRepositoryAndLandscapeToken(
      final Session session, final String landscapeToken, final String repositoryName) {
    return Lists.newArrayList(
        session.query(
            Commit.class,
            """
            MATCH (:Landscape {tokenId: $tokenId})-[:CONTAINS]->(repo:Repository {name: $repoName})
            MATCH (repo)-[:CONTAINS]->(c:Commit)
            OPTIONAL MATCH (c)-[r:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[h:HAS_PARENT]->()
            RETURN DISTINCT c, r, b, h
            ORDER BY c.authorDate ASC;
            """,
            Map.of("tokenId", landscapeToken, "repoName", repositoryName)));
  }

  /**
   * Returns every commit (along with its branch and parent relations) in the specified repository
   * for a given application, ordered by author date (ascending, meaning oldest first).
   */
  public List<Commit> findCommitsWithBranchForApplicationAndLandscapeToken(
      final Session session, final String landscapeToken, final String applicationName) {
    return Lists.newArrayList(
        session.query(
            Commit.class,
            """
            MATCH (l:Landscape {tokenId: $tokenId})-[:CONTAINS]->(a:Application {name: $appName})
            MATCH (repo:Repository)<-[:CONTAINS]-(l)
            WHERE (repo)-[:HAS_ROOT]->(:Directory)-[:CONTAINS*0..]->(:Directory)<-[:HAS_ROOT]-(a)
            MATCH (repo)-[:CONTAINS]->(c:Commit)
            OPTIONAL MATCH (c)-[r:BELONGS_TO]->(b:Branch)
            OPTIONAL MATCH (c)-[h:HAS_PARENT]->()
            RETURN DISTINCT c, r, b, h
            ORDER BY c.authorDate ASC;
            """,
            Map.of("tokenId", landscapeToken, "appName", applicationName)));
  }

  /**
   * Returns the FQNs of all files within the provided application which are present in both the
   * first commit and the second commit, but not under the same FileRevision node, indicating that
   * there has been a change to the file.
   */
  public List<String> findModifiedFileFqns(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String firstCommitHash,
      final String secondCommitHash) {
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
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "firstCommitHash",
                firstCommitHash,
                "secondCommitHash",
                secondCommitHash)));
  }

  /**
   * Returns the FQNs of all files within the provided application which are present in the second
   * commit, but not the first commit, indicating they are added in the second commit.
   */
  public List<String> findAddedFileFqns(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String firstCommitHash,
      final String secondCommitHash) {
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
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "firstCommitHash",
                firstCommitHash,
                "secondCommitHash",
                secondCommitHash)));
  }

  /**
   * Returns the FQNs of all files within the provided application which are present in the first
   * commit, but not the second commit, indicating they are deleted in the second commit.
   */
  public List<String> findDeletedFileFqns(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String firstCommitHash,
      final String secondCommitHash) {
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
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "firstCommitHash",
                firstCommitHash,
                "secondCommitHash",
                secondCommitHash)));
  }

  /**
   * Returns the FQNs of all directories within the provided application which contain files that
   * are present in the second commit, but no files present in the first commit, indicating these
   * directories are added in the second commit.
   */
  public List<String> findAddedDirectoryFqns(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String firstCommitHash,
      final String secondCommitHash) {
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
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "firstCommitHash",
                firstCommitHash,
                "secondCommitHash",
                secondCommitHash)));
  }

  /**
   * Returns the FQNs of all directories within the provided application which contain files that
   * are present in the first commit, but no files present in the second commit, indicating these
   * directories are deleted in the second commit.
   */
  public List<String> findDeletedDirectoryFqns(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String firstCommitHash,
      final String secondCommitHash) {
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
            Map.of(
                "tokenId",
                landscapeToken,
                "appName",
                applicationName,
                "firstCommitHash",
                firstCommitHash,
                "secondCommitHash",
                secondCommitHash)));
  }

  /**
   * Finds all file revisions which are part of the provided application and are contained in the
   * first commit, along with the file's FQNs. Additionally, if a file revision with the same FQN is
   * present in the second commit, it is also returned, allowing the comparison of file attributes.
   * Note that if the exact same file revision is part of both commits, it is returned twice.
   */
  public List<FileComparison> findFilesWithCounterpart(
      final Session session,
      final String landscapeToken,
      final String applicationName,
      final String firstCommitHash,
      final String secondCommitHash) {
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
        Map.of(
            "tokenId",
            landscapeToken,
            "appName",
            applicationName,
            "firstCommitHash",
            firstCommitHash,
            "secondCommitHash",
            secondCommitHash),
        FileComparison.class);
  }

  public Commit getOrCreateCommit(
      final Session session, final String commitHash, final String tokenId) {
    return findCommitByHashAndLandscapeToken(session, commitHash, tokenId)
        .orElse(new Commit(commitHash));
  }

  public record FileComparison(
      String fileFqn, FileRevision fileFirstCommit, FileRevision fileSecondCommit) {}
}
