package net.explorviz.landscape.api.v3.model;

/**
 * Simplified representation of a function call between files.
 *
 * @param id ID of the function
 * @param name Name of the function
 * @param isForward Whether the call was in the forward direction (source -> target)
 * @param requestCount Number of requests to this function
 * @param executionTime Accumulated execution time of all spans for this function
 */
public record SimpleFunctionDto(
    String id, String name, boolean isForward, long requestCount, long executionTime) {}
