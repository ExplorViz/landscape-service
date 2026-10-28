package net.explorviz.landscape.ogm.messaging;

import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;

/** Represents a message destination (e.g. topic, queue) in a message broker system. */
@NodeEntity
public class MessagingDestination {

  @Id @GeneratedValue private Long id;

  /**
   * Name of the destination, or alternatively a template name if the destination is
   * high-cardinality (e.g. "/customers/{customerId}").
   */
  private String name;

  public MessagingDestination() {
    // Empty constructor required by Neo4j OGM
  }

  public MessagingDestination(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }
}
