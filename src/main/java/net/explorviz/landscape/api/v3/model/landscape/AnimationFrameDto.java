package net.explorviz.landscape.api.v3.model.landscape;

import java.util.Objects;

/**
 * One frame of the evolution animation: the landscape state stamped with the git commit it
 * represents and its global position in the repository's history.
 *
 * @param commitHash the target commit this frame represents
 * @param authorDate author date of that commit, epoch millis
 * @param ordinal global index of the commit in authorDate-ascending order
 * @param landscape the flat landscape (diff of commit[ordinal-1] -> commit[ordinal])
 */
public record AnimationFrameDto(
    String commitHash, long authorDate, int ordinal, FlatLandscapeDto landscape) {

  public AnimationFrameDto {
    Objects.requireNonNull(commitHash);
    Objects.requireNonNull(landscape);
  }
}
