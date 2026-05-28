package net.explorviz.landscape.api.v3.model;

/**
 * Summary of a metric across multiple entities.
 *
 * @param min Minimum value found
 * @param max Maximum value found
 */
public record MetricSummaryDto(double min, double max) {}
