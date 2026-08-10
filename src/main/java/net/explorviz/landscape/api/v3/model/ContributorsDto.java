package net.explorviz.landscape.api.v3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

/**
 * Represents the List of all contributors with identity and metrics information. Also holds a total
 * time range of the contributor activity.
 *
 * @param contributors the list of contributors contained
 * @param timeRange the total time range of the repository lifetime
 */
@RegisterForReflection
public record ContributorsDto(List<ContributorDto> contributors, TimeRange timeRange) {
  /**
   * Represents a single contributor.
   *
   * @param contributorId the id of the contributor
   * @param gitUsername the git username of the contributor
   * @param githubLogin the GitHub login of the contributor
   * @param email the email of the contributor
   * @param avatarUrl the avatar url of the contributor
   * @param commitCount the aggregated commit count of the contributor
   * @param isCore whether the contributor is part of the core contributor set
   */
  public record ContributorDto(
      Long contributorId,
      String gitUsername,
      String githubLogin,
      String email,
      String avatarUrl,
      Integer commitCount,
      Boolean isCore) {}

  /**
   * Represents the time range of the repository lifetime.
   *
   * @param from the date of the first commit in the repository.
   * @param to the date of the last commit in the repository.
   */
  public record TimeRange(Long from, Long to) {}
}
