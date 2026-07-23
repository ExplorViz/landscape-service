package net.explorviz.landscape.api.v3.model.landscape;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AnimationSkeletonDto(
    FlatLandscapeDto landscape,
    Map<String, Integer> fqnToFirstOrdinal,
    List<String> orderedCommitHashes) {

  public AnimationSkeletonDto {
    Objects.requireNonNull(landscape);
    Objects.requireNonNull(fqnToFirstOrdinal);
    Objects.requireNonNull(orderedCommitHashes);
  }
}
