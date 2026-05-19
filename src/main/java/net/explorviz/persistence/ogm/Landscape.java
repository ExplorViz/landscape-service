package net.explorviz.persistence.ogm;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a particular visualization landscape. The landscape is the base container node that
 * relates incoming analysis data to a landscape token created by the user.
 */
@NodeEntity
public class Landscape {

  /** Unique identifier used to match incoming analysis data to this landscape. */
  @Id private String tokenId;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Trace> traces = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Repository> repositories = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Application> applications = new HashSet<>();

  public Landscape() {
    // Empty constructor required by Neo4j OGM
  }

  public Landscape(final String tokenId) {
    this.tokenId = tokenId;
  }

  public String getTokenId() {
    return tokenId;
  }

  public Set<Trace> getTraces() {
    return Set.copyOf(traces);
  }

  public void addTrace(final Trace trace) {
    traces.add(trace);
  }

  public Set<Repository> getRepositories() {
    return Set.copyOf(repositories);
  }

  public void addRepository(final Repository repo) {
    repositories.add(repo);
  }

  public Set<Application> getApplications() {
    return Set.copyOf(applications);
  }

  public void addApplication(final Application application) {
    applications.add(application);
  }
}
