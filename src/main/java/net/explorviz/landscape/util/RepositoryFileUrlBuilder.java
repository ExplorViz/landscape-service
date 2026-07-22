package net.explorviz.landscape.util;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/** Builds browser URLs for source files in common Git hosting platforms. */
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public final class RepositoryFileUrlBuilder {

  private RepositoryFileUrlBuilder() {
    // utility class
  }

  public static Optional<String> buildFileUrl(
      final String repositoryUrl,
      final String commitSha,
      final String fqn,
      final String repositoryName) {
    if (repositoryUrl == null || repositoryUrl.isBlank()) {
      return Optional.empty();
    }

    final String filePath = fqnToRepositoryFilePath(fqn, repositoryName);
    if (filePath.isBlank()) {
      return Optional.empty();
    }

    return buildFileUrlForHost(normalizeRepositoryUrl(repositoryUrl), commitSha, filePath);
  }

  static String fqnToRepositoryFilePath(final String fqn, final String repositoryName) {
    if (fqn == null || fqn.isBlank()) {
      return "";
    }

    final String normalizedFqn = fqn.replace('\\', '/');
    if (repositoryName != null
        && !repositoryName.isBlank()
        && normalizedFqn.startsWith(repositoryName + "/")) {
      return normalizedFqn.substring(repositoryName.length() + 1);
    }

    return normalizedFqn;
  }

  private static String normalizeRepositoryUrl(final String repositoryUrl) {
    return repositoryUrl.endsWith(".git")
        ? repositoryUrl.substring(0, repositoryUrl.length() - 4)
        : repositoryUrl;
  }

  private static Optional<String> buildFileUrlForHost(
      final String repositoryUrl, final String commitSha, final String filePath) {
    if (commitSha == null || commitSha.isBlank() || filePath == null || filePath.isBlank()) {
      return Optional.empty();
    }

    final String normalizedFilePath = filePath.replace('\\', '/');
    final String host = URI.create(repositoryUrl).getHost().toLowerCase(Locale.ROOT);

    if (host.contains("github.com")) {
      return Optional.of(repositoryUrl + "/blob/" + commitSha + "/" + normalizedFilePath);
    }
    if (host.contains("gitlab")) {
      return Optional.of(repositoryUrl + "/-/blob/" + commitSha + "/" + normalizedFilePath);
    }
    if (host.contains("bitbucket.org")) {
      return Optional.of(repositoryUrl + "/src/" + commitSha + "/" + normalizedFilePath);
    }

    return Optional.empty();
  }
}
