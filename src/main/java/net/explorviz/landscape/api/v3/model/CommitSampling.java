package net.explorviz.landscape.api.v3.model;

import jakarta.ws.rs.BadRequestException;
import java.util.Locale;

/** Sampling strategy for commit-tree responses. */
public enum CommitSampling {
  NONE,
  DAILY,
  MONTHLY,
  YEARLY;

  public static CommitSampling fromQueryParam(final String value) {
    if (value == null || value.isBlank()) {
      return NONE;
    }

    final String normalized = value.trim().toUpperCase(Locale.ROOT);
    for (final CommitSampling sampling : values()) {
      if (sampling.name().equals(normalized)) {
        return sampling;
      }
    }

    throw new BadRequestException(
        "Invalid sampling value '" + value + "'. Allowed values: none, daily, monthly, yearly.");
  }
}
