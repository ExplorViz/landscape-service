package net.explorviz.landscape.api.v3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public record ContributorsDto(List<ContributorDto> contributors, TimeRange timeRange) {
  public record ContributorDto(
      Long contributorId,
      String gitUsername,
      String githubLogin,
      String email,
      String avatarUrl,
      Integer commitCount,
      Boolean isCore) {}

  public record TimeRange(Long from, Long to) {}
}
