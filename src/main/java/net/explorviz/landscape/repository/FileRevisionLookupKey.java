package net.explorviz.landscape.repository;

import net.explorviz.landscape.proto.FileData;

/** Canonical cache / index key for resolving a {@code FileRevision} without a graph traversal. */
public record FileRevisionLookupKey(
    String landscapeToken, String repoName, String filePath, String hash) {

  public String cacheKey() {
    return landscapeToken + "/" + repoName + "/" + filePath + "/" + hash;
  }

  public static FileRevisionLookupKey fromFileData(final FileData file) {
    return new FileRevisionLookupKey(
        file.getLandscapeToken(), file.getRepositoryName(), file.getFilePath(), file.getFileHash());
  }
}
