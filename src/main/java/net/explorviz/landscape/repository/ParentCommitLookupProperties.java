package net.explorviz.landscape.repository;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "explorviz.landscape.parent-commit-lookup")
public interface ParentCommitLookupProperties {

  /** Maximum number of graph lookups when resolving a parent commit internal id. */
  @WithDefault("5")
  int maxAttempts();

  /** Delay in milliseconds between lookup attempts. */
  @WithDefault("20")
  long retryDelayMs();

  /** Lookup attempts when unchanged-file inheritance requires a persisted parent. */
  @WithDefault("50")
  int requiredMaxAttempts();

  /** Delay in milliseconds between attempts when a persisted parent is required. */
  @WithDefault("100")
  long requiredRetryDelayMs();
}
