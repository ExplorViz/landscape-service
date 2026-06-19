package net.explorviz.landscape.api.v3.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Map;

/**
 * Represents a single commit within a branch, including accumulated repository-level metrics when
 * available.
 *
 * @param hash Git commit hash
 * @param metrics Summed metrics across all file revisions in this commit
 * @param hasAccumulatedMetrics Whether all file revisions have been analyzed and metrics were
 *     aggregated
 */
public record CommitNodeDto(
    String hash,
    @JsonInclude(Include.NON_EMPTY) Map<String, Double> metrics,
    boolean hasAccumulatedMetrics) {}
