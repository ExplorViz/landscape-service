package net.explorviz.persistence.api.v3.model;

import java.util.Map;

/**
 * Represents a variable captured in a debug snapshot together with its current value and associated
 * metrics.
 *
 * @param id Unique identifier of the variable snapshot
 * @param name Name of the captured variable
 * @param type Declared type of the variable
 * @param value Captured value of the variable at the time of the snapshot
 * @param metrics Numerical metrics associated with the variable, such as the number of
 *     modifications observed during the debugging session
 */

public record VariableDto(
    String id,
    String name,
    String type,
    String value,
    Map<String, Double> metrics) {}
