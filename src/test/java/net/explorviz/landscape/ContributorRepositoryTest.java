package net.explorviz.landscape;

import static net.explorviz.landscape.util.TestUtils.resetDatabase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import net.explorviz.landscape.ogm.Contributor;
import net.explorviz.landscape.proto.ContributorData;
import net.explorviz.landscape.repository.ContributorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.ogm.session.Session;
import org.neo4j.ogm.session.SessionFactory;

@QuarkusTest
public class ContributorRepositoryTest {
  @Inject ContributorRepository contributorRepository;
  @Inject SessionFactory sessionFactory;

  private Session session;

  @BeforeEach
  void cleanup() {
    session = sessionFactory.openSession();
    resetDatabase(session);
  }

  @Test
  void testGetOrCreateNewContributor() {
    Session session = sessionFactory.openSession();
    ContributorData data =
        ContributorData.newBuilder()
            .setEmail("new@example.com")
            .setGitUsername("newuser")
            .setGithubLogin("newlogin")
            .setAvatarUrl("http://avatar.com/new")
            .build();

    Contributor contributor = contributorRepository.getOrCreateContributor(session, data);

    assertTrue(contributor.getId() != null);
    assertEquals("new@example.com", contributor.getEmail());
    assertEquals("newuser", contributor.getGitUsername());
    assertEquals("newlogin", contributor.getGithubLogin());
    assertEquals("http://avatar.com/new", contributor.getAvatarUrl());
  }

  @Test
  void testGetOrCreateUpdateMissingGitUsernamePresent() {
    Session session = sessionFactory.openSession();

    Contributor existing = new Contributor("existinguser", "exist@example.com", null, null);
    session.save(existing);

    ContributorData data =
        ContributorData.newBuilder()
            .setEmail("exist@example.com")
            .setGitUsername("existinguser")
            .setGithubLogin("existlogin")
            .setAvatarUrl("http://avatar.com/exist")
            .build();

    Contributor contributor = contributorRepository.getOrCreateContributor(session, data);

    assertEquals(existing.getId(), contributor.getId());
    assertEquals("exist@example.com", contributor.getEmail());
    assertEquals("existinguser", contributor.getGitUsername());
    assertEquals("existlogin", contributor.getGithubLogin());
    assertEquals("http://avatar.com/exist", contributor.getAvatarUrl());
  }

  @Test
  void testGetOrCreateUpdateMissingGithubLoginPresent() {
    Session session = sessionFactory.openSession();

    Contributor existing = new Contributor("gituser", null, "github_only", "http://avatar.com/old");
    session.save(existing);

    ContributorData data =
        ContributorData.newBuilder()
            .setEmail("found@example.com")
            .setGitUsername("gituser")
            .setGithubLogin("github_only")
            .setAvatarUrl("http://avatar.com/old")
            .build();

    Contributor contributor = contributorRepository.getOrCreateContributor(session, data);

    assertEquals(existing.getId(), contributor.getId());
    assertEquals("found@example.com", contributor.getEmail());
    assertEquals("github_only", contributor.getGithubLogin());
  }
}
