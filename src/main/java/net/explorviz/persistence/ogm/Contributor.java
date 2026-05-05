package net.explorviz.persistence.ogm;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

@NodeEntity
@RegisterForReflection
public class Contributor {
  @Id @GeneratedValue private Long id;

  private String name;
  private String email;
  private String username;
  private String avatarUrl;

  //  @Relationship(type = "CREATED", direction = Relationship.Direction.OUTGOING)
  //  private Set<Issue> issues = new HashSet<>();
  //
  //  @Relationship(type = "OPENED", direction = Relationship.Direction.OUTGOING)
  //  private Set<PullRequest> pullRequests = new HashSet<>();

  @Relationship(type = "AUTHORED", direction = Relationship.Direction.OUTGOING)
  private Set<Commit> commits = new HashSet<>();

  public Contributor() {
    // Empty constructor required by Neo4j OGM
    this.commits = new HashSet<>();
  }

  public Contributor(final String name) {
    this.name = name;
  }

  public Contributor(final String name, final String email) {
    this.name = name;
    this.email = email;
  }

  public Contributor(
      final String name, final String email, final String username, final String avatarUrl) {
    this.name = name;
    this.email = email;
    this.username = username;
    this.avatarUrl = avatarUrl;
  }

  // Getters and setters for all fields
  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getAvatarUrl() {
    return avatarUrl;
  }

  public void setAvatarUrl(final String avatarUrl) {
    this.avatarUrl = avatarUrl;
  }

  public Set<Commit> getCommits() {
    return commits;
  }

  public void setCommits(final Set<Commit> commits) {
    this.commits = commits;
  }

  public void addCommit(final Commit commit) {
    if (this.commits == null) {
      this.commits = new HashSet<>();
    }
    this.commits.add(commit);
  }

  public void removeCommit(final Commit commit) {
    if (this.commits != null) {
      this.commits.remove(commit);
    }
  }

  //  public Set<Issue> getIssues() {
  //    return issues;
  //  }
  //
  //  public void setIssues(final Set<Issue> issues) {
  //    this.issues = issues;
  //  }
  //
  //  public void addIssue(final Issue issue) {
  //    if (this.issues == null) {
  //      this.issues = new HashSet<>();
  //    }
  //    this.issues.add(issue);
  //  }
  //
  //  public Set<PullRequest> getPullRequests() {
  //    return pullRequests;
  //  }
  //
  //  public void setPullRequests(final Set<PullRequest> pullRequests) {
  //    this.pullRequests = pullRequests;
  //  }
  //
  //  public void addPullRequest(final PullRequest pullRequest) {
  //    if (this.pullRequests == null) {
  //      this.pullRequests = new HashSet<>();
  //    }
  //    this.pullRequests.add(pullRequest);
  //  }
}
