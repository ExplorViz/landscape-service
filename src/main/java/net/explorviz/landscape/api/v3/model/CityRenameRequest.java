package net.explorviz.landscape.api.v3.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Request body for renaming a city (Application or DatabaseSystem) in the landscape.
 *
 * @param name New display name for the city. Must be non-blank.
 */
@RegisterForReflection
public record CityRenameRequest(String name) {}
