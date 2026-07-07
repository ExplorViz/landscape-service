package net.explorviz.landscape.ogm;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.HashSet;
import java.util.Set;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

@NodeEntity
@RegisterForReflection
public class PullRequest extends TrackableResource {

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final Set<Commit> commits = new HashSet<>();

  @Relationship(type = "REFERENCES", direction = Relationship.Direction.OUTGOING)
  private final Set<Issue> referencedIssues = new HashSet<>();

  // store raw hashes for use to relink after analysis
  private Set<String> commitHashes = new HashSet<>();

  public Set<Issue> getReferencedIssues() {
    return referencedIssues;
  }

  public Set<Commit> getCommits() {
    return commits;
  }

  public void addCommit(final Commit commit) {
    commits.add(commit);
  }

  public void addReferencedIssue(final Issue issue) {
    referencedIssues.add(issue);
  }

  public Set<String> getCommitHashes() {
    return commitHashes;
  }

  public void setCommitHashes(final Set<String> commitHashes) {
    this.commitHashes = commitHashes;
  }
}
