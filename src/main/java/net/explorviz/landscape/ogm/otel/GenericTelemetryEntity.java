package net.explorviz.landscape.ogm.otel;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents an entity extracted from telemetry which cannot be further classified. */
@NodeEntity
public class GenericTelemetryEntity {
  @Id @GeneratedValue private Long id;

  private String name;

  /**
   * Identifier for looking up telemetry data related to this entity (e.g. finding communication).
   * This value is not unique across different commits; use in conjunction with the commit hash to
   * find telemetry for one specific commit only.
   */
  private String telemetryKey;

  public String getName() {
    return name;
  }
}
