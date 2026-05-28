package net.explorviz.landscape.api.v3.model;

import java.util.List;
import java.util.Map;

/**
 * Represents aggregated communication between entities including metric summaries.
 *
 * @param metrics Summary of metrics across all communications
 * @param communications List of individual communication edges
 */
public record AggregatedEntityCommunicationDto(
    Map<String, MetricSummaryDto> metrics, List<CommunicationDto> communications) {}
