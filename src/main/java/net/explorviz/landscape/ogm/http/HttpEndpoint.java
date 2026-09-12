package net.explorviz.landscape.ogm.http;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/**
 * Represents an HTTP API endpoint, identified by its route. A single endpoint may support multiple
 * HTTP methods (e.g. GET, POST, ...).
 */
@NodeEntity(label = "HTTPEndpoint")
public class HttpEndpoint {
  @Id @GeneratedValue private Long id;

  /**
   * The name of the endpoint. This should correspond to the route template of the request's URL
   * path. Dynamic segments in the path (like an ID) should be represented by placeholders.
   */
  private String name;

  /**
   * Identifier for looking up telemetry data related to this endpoint (e.g. finding communication).
   * This value is not unique across different commits; use in conjunction with the commit hash to
   * find telemetry for one specific commit only.
   */
  private String telemetryKey;

  /** The HTTP methods known to be supported by this endpoint (e.g. GET, POST, ...) * */
  private final Set<String> supportedMethods = new HashSet<>();

  public HttpEndpoint() {
    // Empty constructor required by Neo4j OGM
  }

  public HttpEndpoint(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
