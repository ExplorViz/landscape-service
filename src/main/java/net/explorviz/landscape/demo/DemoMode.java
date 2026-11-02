package net.explorviz.landscape.demo;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Shared deployment switch for read-only online demos. */
@ApplicationScoped
public class DemoMode {
  public static final String MESSAGE = "Demo mode: adding, changing, or deleting data is disabled.";

  @ConfigProperty(name = "explorviz.demo-mode", defaultValue = "false")
  boolean enabled;

  public boolean isEnabled() {
    return enabled;
  }
}
