package net.explorviz.landscape.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryFileUrlBuilderTest {

  @Test
  void buildsGithubFileUrlFromFqn() {
    final Optional<String> fileUrl =
        RepositoryFileUrlBuilder.buildFileUrl(
            "https://github.com/spring-projects/spring-petclinic.git",
            "abc123",
            "spring-petclinic/src/main/java/App.java",
            "spring-petclinic");

    assertTrue(fileUrl.isPresent());
    assertEquals(
        "https://github.com/spring-projects/spring-petclinic/blob/abc123/src/main/java/App.java",
        fileUrl.get());
  }

  @Test
  void buildsGitlabFileUrlFromFqn() {
    final Optional<String> fileUrl =
        RepositoryFileUrlBuilder.buildFileUrl(
            "https://gitlab.com/group/project.git", "abc123", "project/src/main.rs", "project");

    assertTrue(fileUrl.isPresent());
    assertEquals("https://gitlab.com/group/project/-/blob/abc123/src/main.rs", fileUrl.get());
  }

  @Test
  void stripsRepositoryPrefixFromFqn() {
    assertEquals(
        "apps/service-a/src/Main.java",
        RepositoryFileUrlBuilder.fqnToRepositoryFilePath(
            "myrepo/apps/service-a/src/Main.java", "myrepo"));
  }

  @Test
  void returnsEmptyForUnknownHost() {
    final Optional<String> fileUrl =
        RepositoryFileUrlBuilder.buildFileUrl(
            "https://example.com/org/project.git", "abc123", "project/README.md", "project");

    assertTrue(fileUrl.isEmpty());
  }
}
