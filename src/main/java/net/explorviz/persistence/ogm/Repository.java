package net.explorviz.persistence.ogm;

import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.Relationship.Direction;

@NodeEntity
public class Repository {
  @Id @GeneratedValue private Long id;

  private String name;
  private static final String CONTAINS = "CONTAINS";

  @Relationship(type = CONTAINS, direction = Relationship.Direction.OUTGOING)
  private Set<Commit> commits = new HashSet<>();

  @Relationship(type = CONTAINS, direction = Relationship.Direction.OUTGOING)
  private Set<Branch> branches = new HashSet<>();

  @Relationship(type = CONTAINS, direction = Direction.OUTGOING)
  private Set<Issue> issues = new HashSet<>();

  @Relationship(type = CONTAINS, direction = Direction.OUTGOING)
  private Set<PullRequest> pullRequests = new HashSet<>();

  @Relationship(type = "HAS_ROOT", direction = Relationship.Direction.OUTGOING)
  private Directory rootDirectory;

  @Relationship(type = CONTAINS, direction = Relationship.Direction.OUTGOING)
  private Set<Tag> tags = new HashSet<>();

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

  public void addCommit(final Commit commit) {
    final Set<Commit> newCommits = new HashSet<>(commits);
    newCommits.add(commit);
    commits = Set.copyOf(newCommits);
  }

  public void addBranch(final Branch branch) {
    final Set<Branch> newBranches = new HashSet<>(branches);
    newBranches.add(branch);
    branches = Set.copyOf(newBranches);
  }

  public void setRootDirectory(final Directory directory) {
    this.rootDirectory = directory;
  }

  public void addTag(final Tag tag) {
    final Set<Tag> newTags = new HashSet<>(tags);
    newTags.add(tag);
    tags = Set.copyOf(newTags);
  }

  public void addIssue(final Issue issue) {
    final Set<Issue> newIssues = new HashSet<>(issues);
    newIssues.add(issue);
    issues = Set.copyOf(newIssues);
  }

  public void addPullRequest(final PullRequest pullRequest) {
    final Set<PullRequest> newPullRequests = new HashSet<>(pullRequests);
    newPullRequests.add(pullRequest);
    pullRequests = Set.copyOf(newPullRequests);
  }

  public Directory getRootDirectory() {
    return this.rootDirectory;
  }
}
