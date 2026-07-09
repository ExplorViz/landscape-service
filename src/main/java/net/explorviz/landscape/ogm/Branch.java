package net.explorviz.landscape.ogm;

import java.time.Instant;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.typeconversion.DateLong;

/** Represents a git branch. */
@NodeEntity
public class Branch {

  @Id @GeneratedValue private Long id;

  private String name;

  /**
   * Cached hash of the newest fully persisted commit on this branch. Updated when a commit's file
   * data is complete so {@code getStateData} can avoid scanning commits that were skipped by the
   * analyzer.
   */
  private String latestFullyPersistedCommitHash;

  @DateLong private Instant latestFullyPersistedCommitDate;

  public Branch() {
    // Empty constructor required by Neo4j OGM
  }

  public Branch(final String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public String getLatestFullyPersistedCommitHash() {
    return latestFullyPersistedCommitHash;
  }

  public void setLatestFullyPersistedCommitHash(final String latestFullyPersistedCommitHash) {
    this.latestFullyPersistedCommitHash = latestFullyPersistedCommitHash;
  }

  public Instant getLatestFullyPersistedCommitDate() {
    return latestFullyPersistedCommitDate;
  }

  public void setLatestFullyPersistedCommitDate(final Instant latestFullyPersistedCommitDate) {
    this.latestFullyPersistedCommitDate = latestFullyPersistedCommitDate;
  }
}
