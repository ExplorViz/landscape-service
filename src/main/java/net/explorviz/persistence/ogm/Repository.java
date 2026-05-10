package net.explorviz.persistence.ogm;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/** Represents a git repository. */
@NodeEntity
public class Repository {

  @Id @GeneratedValue private Long id;

  private String name;

  @Relationship(type = "HAS_ROOT", direction = Relationship.Direction.OUTGOING)
  private Directory rootDirectory;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Commit> commits = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Branch> branches = new HashSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Tag> tags = new HashSet<>();

  @Relationship(type = "HAS_DEBUG_RUN", direction = Relationship.Direction.OUTGOING)
  private final Set<DebugRun> debugRuns = new HashSet<>();

  public Repository() {
    // Empty constructor required by Neo4j OGM
  }

  public Repository(final String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public Directory getRootDirectory() {
    return rootDirectory;
  }

  public void setRootDirectory(final Directory rootDirectory) {
    this.rootDirectory = rootDirectory;
  }

  public Set<Commit> getCommits() {
    return Set.copyOf(commits);
  }

  public void addCommit(final Commit commit) {
    commits.add(commit);
  }

  public Set<Branch> getBranches() {
    return Set.copyOf(branches);
  }

  public void addBranch(final Branch branch) {
    branches.add(branch);
  }

  public Set<Tag> getTags() {
    return Set.copyOf(tags);
  }

  public void addTag(final Tag tag) {
    tags.add(tag);
  }

  public Set<DebugRun> getDebugRuns() {
    return Set.copyOf(debugRuns);
  }

  public void addDebugRun(final DebugRun debugRun) {
    debugRuns.add(debugRun);
  }
}
