package net.explorviz.landscape.ogm;

import org.neo4j.ogm.annotation.EndNode;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.RelationshipEntity;
import org.neo4j.ogm.annotation.StartNode;

// TO BE IMPLEMENTED, NOT WORKING YET!
@RelationshipEntity(type = "CHANGED")
public class CommitFileChange {
  @Id @GeneratedValue Long id;
  @StartNode Commit commit;
  @EndNode FileRevision fileRevision;
  String changeType;

  public CommitFileChange() {
    // required by Neo4j OGM
  }

  public CommitFileChange(
      final Commit commit, final FileRevision fileRevision, final String changeType) {
    this.commit = commit;
    this.fileRevision = fileRevision;
    this.changeType = changeType;
  }

  public Long getId() {
    return id;
  }

  public Commit getCommit() {
    return commit;
  }

  public FileRevision getFileRevision() {
    return fileRevision;
  }

  public String getChangeType() {
    return changeType;
  }
}
