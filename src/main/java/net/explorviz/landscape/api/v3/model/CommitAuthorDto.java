package net.explorviz.landscape.api.v3.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Represents the author of a git commit.
 *
 * @param contributorId Internal contributor identifier
 * @param gitUsername Git author name from version control
 * @param githubLogin GitHub login when available
 * @param email Author email when available
 */
public record CommitAuthorDto(
    @JsonInclude(Include.NON_NULL) Long contributorId,
    @JsonInclude(Include.NON_NULL) String gitUsername,
    @JsonInclude(Include.NON_NULL) String githubLogin,
    @JsonInclude(Include.NON_NULL) String email) {}
