package net.explorviz.persistence.ogm;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/** Abstract class for similar data types of issues and pull requests. */
@NodeEntity
@SuppressWarnings("PMD.AbstractClassWithoutAbstractMethod")
public abstract class TrackableResource {
  @Id @GeneratedValue private Long id;

  private Integer number;
  private String title;
  private String state;
  private Set<String> labels;

  @Relationship(type = "HAS_VERSION", direction = Relationship.Direction.OUTGOING)
  private Set<ResourceVersion> versions = new HashSet<>();

  public TrackableResource() {
    // Empty constructor required by Neo4j OGM
  }

  public TrackableResource(
      final Integer number, final String title, final String state, final Set<String> labels) {
    this.number = number;
    this.title = title;
    this.state = state;
    this.labels = labels;
  }

  public Long getId() {
    return id;
  }

  public Integer getNumber() {
    return number;
  }

  public void setNumber(final Integer number) {
    this.number = number;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(final String title) {
    this.title = title;
  }

  public String getState() {
    return state;
  }

  public void setState(final String state) {
    this.state = state;
  }

  public Set<String> getLabels() {
    return labels;
  }

  public void setLabels(final Set<String> labels) {
    this.labels = labels;
  }

  public void addLabel(final String label) {
    if (this.labels == null) {
      this.labels = new HashSet<>();
    }
    this.labels.add(label);
  }

  public Set<ResourceVersion> getVersions() {
    return versions;
  }

  public void setVersions(final Set<ResourceVersion> versions) {
    this.versions = versions;
  }

  public void addVersion(final ResourceVersion version) {
    if (this.versions == null) {
      this.versions = new HashSet<>();
    }
    this.versions.add(version);
  }

  // TODO: cleaner way or handle in repo?
  public ResourceVersion getCurrentVersion() {
    if (this.versions == null || this.versions.isEmpty()) {
      return null;
    } else {
      return this.versions.stream()
          .max((v1, v2) -> v1.getCreationDate().compareTo(v2.getCreationDate()))
          .orElse(null);
    }
  }
}
