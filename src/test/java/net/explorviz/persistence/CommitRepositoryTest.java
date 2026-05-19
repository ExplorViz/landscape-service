package net.explorviz.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.explorviz.persistence.ogm.Branch;
import net.explorviz.persistence.ogm.Commit;
import net.explorviz.persistence.ogm.FileRevision;
import net.explorviz.persistence.ogm.Landscape;
import net.explorviz.persistence.ogm.Repository;
import net.explorviz.persistence.repository.CommitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
class CommitRepositoryTest {

  @Inject CommitRepository commitRepository;

  @Inject SessionFactory sessionFactory;

  @BeforeEach
  void cleanup() {
    Session session = sessionFactory.openSession();
    session.purgeDatabase();
  }

  @Test
  void testFindLatestCommit() {
    Session session = sessionFactory.openSession();

    FileRevision file1 = new FileRevision("File1.java");
    file1.setHasFileData(true);

    FileRevision file2 = new FileRevision("File2.java");
    file2.setHasFileData(true);

    FileRevision file3 = new FileRevision("File3.java");

    Branch branch = new Branch("main");

    Commit commit1 = new Commit("commit1");
    commit1.addFileRevision(file1);
    commit1.setCommitDate(Instant.ofEpochMilli(1000));
    commit1.setBranch(branch);

    Commit commit2 = new Commit("commit2");
    commit2.addParentCommit(commit1);
    commit2.addFileRevision(file1);
    commit2.addFileRevision(file2);
    commit2.setCommitDate(Instant.ofEpochMilli(2000));
    commit2.setBranch(branch);

    Commit commit3 = new Commit("commit3");
    commit3.addParentCommit(commit2);
    commit3.addFileRevision(file1);
    commit3.addFileRevision(file2);
    commit3.addFileRevision(file3);
    commit3.setCommitDate(Instant.ofEpochMilli(3000));
    commit3.setBranch(branch);

    Repository repository = new Repository("myrepo");
    repository.addBranch(branch);
    repository.addCommit(commit1);
    repository.addCommit(commit2);
    repository.addCommit(commit3);

    Landscape landscape = new Landscape("mytokenvalue");
    landscape.addRepository(repository);

    session.save(landscape);

    Optional<Commit> latestCommit =
        commitRepository.findLatestFullyPersistedCommit(
            session, "myrepo", "mytokenvalue", "main", List.of());

    assertTrue(latestCommit.isPresent());
    assertEquals("commit2", latestCommit.get().getHash());
  }
}
