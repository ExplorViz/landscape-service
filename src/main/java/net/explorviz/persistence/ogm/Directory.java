package net.explorviz.persistence.ogm;

import java.util.SortedSet;
import java.util.TreeSet;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a file system directory. Note that unlike the {@link FileRevision}, Directory nodes
 * are not versioned based on git commits or according to their origin of data; they are purely
 * organizational. Therefore, they are uniquely identified within an {@link Application} or a {@link
 * Repository} by their path alone, and a Directory can contain multiple FileRevisions of the same
 * name as long as the hashes differ.
 */
@NodeEntity
public class Directory implements Comparable<Directory> {

  @Id @GeneratedValue private Long id;

  private String name;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Directory> subdirectories = new TreeSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<FileRevision> fileRevisions = new TreeSet<>();

  public Directory() {
    // Empty constructor required by Neo4j OGM
  }

  public Directory(final String name) {
    this.name = name;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public SortedSet<Directory> getSubdirectories() {
    return new TreeSet<>(subdirectories);
  }

  public void addSubdirectory(final Directory directory) {
    subdirectories.add(directory);
  }

  public SortedSet<FileRevision> getFileRevisions() {
    return new TreeSet<>(fileRevisions);
  }

  public void addFileRevision(final FileRevision fileRevision) {
    fileRevisions.add(fileRevision);
  }

  @Override
  public int compareTo(final Directory other) {
    return name.compareTo(other.name);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final Directory otherDirectory)) {
      return false;
    }

    return id != null && id.equals(otherDirectory.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
