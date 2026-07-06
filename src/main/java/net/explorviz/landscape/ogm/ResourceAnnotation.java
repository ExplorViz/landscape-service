package net.explorviz.landscape.ogm;

import java.time.Instant;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.typeconversion.DateLong;

@NodeEntity
public class ResourceAnnotation {
  @Id @GeneratedValue private Long id;

  @DateLong private Instant timestamp;
  private String externalId;
  private AnnotationType annotationType;

  @Relationship(type = "GENERATES", direction = Relationship.Direction.OUTGOING)
  private ResourceVersion generatedResourceVersion;

  @Relationship(type = "USED", direction = Relationship.Direction.OUTGOING)
  private ResourceVersion usedResource;

  @Relationship(type = "WAS_ASSOCIATED_WITH", direction = Relationship.Direction.OUTGOING)
  private Contributor associatedContributor;

  public ResourceAnnotation() {
    // empty Constructor required by Neo4j OGM
  }

  public ResourceAnnotation(
      final Instant timestamp, final String externalId, final AnnotationType annotationType) {
    this.timestamp = timestamp;
    this.externalId = externalId;
    this.annotationType = annotationType;
  }

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public Instant getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(final Instant timestamp) {
    this.timestamp = timestamp;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(final String externalId) {
    this.externalId = externalId;
  }

  public AnnotationType getAnnotationType() {
    return annotationType;
  }

  public void setAnnotationType(final AnnotationType annotationType) {
    this.annotationType = annotationType;
  }

  public ResourceVersion getGeneratedResourceVersion() {
    return generatedResourceVersion;
  }

  public void setGeneratedResourceVersion(final ResourceVersion generatedResourceVersion) {
    this.generatedResourceVersion = generatedResourceVersion;
  }

  public ResourceVersion getUsedResource() {
    return usedResource;
  }

  public void setUsedResource(final ResourceVersion resource) {
    this.usedResource = resource;
  }

  public Contributor getAssociatedContributor() {
    return associatedContributor;
  }

  public void setAssociatedContributor(final Contributor associatedContributor) {
    this.associatedContributor = associatedContributor;
  }
}
