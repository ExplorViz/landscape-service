package net.explorviz.landscape.ogm;

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

  private String gitUsername;
  private String email;
  private String githubLogin;
  private String avatarUrl;

  @Relationship(type = "AUTHORED", direction = Relationship.Direction.OUTGOING)
  private Set<Commit> commits = new HashSet<>();

  public Contributor() {
    // Empty constructor required by Neo4j OGM
    this.commits = new HashSet<>();
  }

  public Contributor(final String gitUsername) {
    this.gitUsername = gitUsername;
  }

  public Contributor(final String gitUsername, final String email) {
    this.gitUsername = gitUsername;
    this.email = email;
  }

  public Contributor(
      final String gitUsername,
      final String email,
      final String githubLogin,
      final String avatarUrl) {
    this.gitUsername = gitUsername;
    this.email = email;
    this.githubLogin = githubLogin;
    this.avatarUrl = avatarUrl;
  }

  // Getters and setters for all fields
  public Long getId() {
    return id;
  }

  public String getGitUsername() {
    return gitUsername;
  }

  public void setGitUsername(final String gitUsername) {
    this.gitUsername = gitUsername;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getGithubLogin() {
    return githubLogin;
  }

  public void setGithubLogin(final String githubLogin) {
    this.githubLogin = githubLogin;
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
}
