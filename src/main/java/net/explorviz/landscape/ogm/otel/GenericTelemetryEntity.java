package net.explorviz.landscape.ogm.otel;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents an entity extracted from telemetry which cannot be further classified. */
@NodeEntity
public class GenericTelemetryEntity {

  /**
   * Name that is given to buildings representing generic entities by default, since these do not
   * have a natural name that can be derived from its attributes.
   */
  public static final String DISPLAY_NAME = "Unclassified Entity";

  @Id @GeneratedValue private Long id;

  @SuppressWarnings("PMD.FinalFieldCouldBeStatic")
  private final String name = DISPLAY_NAME;

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
