package net.explorviz.persistence.ogm;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.Relationship.Direction;
import org.neo4j.ogm.annotation.typeconversion.DateLong;

@NodeEntity
public class ResourceVersion {
  @Id @GeneratedValue private Long id;

  private String externalId;

  @DateLong private Instant creationDate;

  private String title;
  private String description;
  private Set<String> labels = new HashSet<>();
  private String webUrl;
  private ResourceState state;

  @Relationship(type = "CREATED_BY", direction = Direction.OUTGOING)
  private Contributor createdBy;

  @Relationship(type = "DERIVED_FROM", direction = Relationship.Direction.OUTGOING)
  private ResourceVersion derivedFrom;

  @Relationship(type = "GENERATES", direction = Relationship.Direction.INCOMING)
  private ResourceAnnotation generatedBy;

  @Relationship(type = "HAS_VERSION", direction = Relationship.Direction.INCOMING)
  private TrackableResource resource;

  public Long getId() {
    return id;
  }

  public void setId(final Long id) {
    this.id = id;
  }

  public String getExternalId() {
    return externalId;
  }

  public void setExternalId(final String externalId) {
    this.externalId = externalId;
  }

  public Instant getCreationDate() {
    return creationDate;
  }

  public void setCreationDate(final Instant creationDate) {
    this.creationDate = creationDate;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(final String title) {
    this.title = title;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(final String description) {
    this.description = description;
  }

  public Set<String> getLabels() {
    return labels;
  }

  public void setLabels(final Set<String> labels) {
    this.labels = labels;
  }

  public String getWebUrl() {
    return webUrl;
  }

  public void setWebUrl(final String webUrl) {
    this.webUrl = webUrl;
  }

  public Contributor getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(final Contributor createdBy) {
    this.createdBy = createdBy;
  }

  public ResourceVersion getDerivedFrom() {
    return derivedFrom;
  }

  public void setDerivedFrom(final ResourceVersion derivedFrom) {
    this.derivedFrom = derivedFrom;
  }

  public ResourceAnnotation getGeneratedBy() {
    return generatedBy;
  }

  public void setGeneratedBy(final ResourceAnnotation generatedBy) {
    this.generatedBy = generatedBy;
  }

  public TrackableResource getResource() {
    return resource;
  }

  public void setResource(final TrackableResource resource) {
    this.resource = resource;
  }

  public ResourceState getState() {
    return state;
  }

  public void setState(final ResourceState state) {
    this.state = state;
  }
}
