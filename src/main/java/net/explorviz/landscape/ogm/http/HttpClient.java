package net.explorviz.landscape.ogm.http;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents an HTTP client making requests to a server's API. */
@NodeEntity(label = "HTTPClient")
public class HttpClient {

  /**
   * Name that is given to buildings representing HTTP client entities by default, since these do
   * not have a natural name that can be derived from its attributes.
   */
  public static final String DISPLAY_NAME = "HTTP Client";

  @Id @GeneratedValue private Long id;

  @SuppressWarnings({"PMD.FinalFieldCouldBeStatic", "FieldCanBeLocal"})
  private final String name = DISPLAY_NAME;

  /**
   * Identifier for looking up telemetry data related to this client (e.g. finding communication).
   * This value is not unique across different commits; use in conjunction with the commit hash to
   * find telemetry for one specific commit only.
   */
  private String telemetryKey;

  public String getName() {
    return name;
  }
}
