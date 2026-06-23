package net.explorviz.landscape.api.v3.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.time.Instant;
import java.util.Map;

/**
 * Represents a single commit within a branch, including accumulated repository-level metrics when
 * available.
 *
 * @param hash Git commit hash
 * @param commitDate Commit timestamp from version control
 * @param metrics Summed metrics across all file revisions in this commit
 * @param hasAccumulatedMetrics Whether all file revisions have been analyzed and metrics were
 *     aggregated
 */
public record CommitNodeDto(
    String hash,
    @JsonInclude(Include.NON_NULL) Instant commitDate,
    @JsonInclude(Include.NON_EMPTY) Map<String, Double> metrics,
    boolean hasAccumulatedMetrics) {}
