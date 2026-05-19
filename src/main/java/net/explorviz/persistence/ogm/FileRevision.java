package net.explorviz.persistence.ogm;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Properties;
import org.neo4j.ogm.annotation.Relationship;

/**
 * Represents a specific version of a file.
 *
 * <p>A new instance is created for every analyzed file, and whenever an analyzed commit changes a
 * file's contents, as indicated by its {@link #hash}. A FileRevision is therefore uniquely
 * identified by the combination of its path relative to the {@link Application} and / or {@link
 * Repository} and its hash value.
 *
 * <p>For runtime data not associated with any git commit, a FileRevision without a hash can be
 * created, representing the runtime version of that file.
 */
@NodeEntity
public class FileRevision implements Comparable<FileRevision> {

  @Id @GeneratedValue private Long id;

  private String name;

  /**
   * Checksum of file contents as created by git. If a file lacks a file hash, it is considered to
   * be originating from runtime analysis.
   */
  private String hash;

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Clazz> classes = new TreeSet<>();

  @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
  private final SortedSet<Function> functions = new TreeSet<>();

  /**
   * Whether we have seen a {@link net.explorviz.persistence.proto.FileData} message for this file.
   */
  private boolean hasFileData;

  private String language;

  private String packageName;

  private final Set<String> importNames = new HashSet<>();

  private String lastEditor;

  private int addedLines;

  private int modifiedLines;

  private int deletedLines;

  @Properties private final Map<String, Double> metrics = new HashMap<>();

  public FileRevision() {
    // Empty constructor required by Neo4j OGM
  }

  public FileRevision(final String name) {
    this.name = name;
  }

  public FileRevision(final String name, final String hash) {
    this.name = name;
    this.hash = hash;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getHash() {
    return hash;
  }

  public void setHash(final String hash) {
    this.hash = hash;
  }

  public SortedSet<Clazz> getClasses() {
    return new TreeSet<>(classes);
  }

  public void addClass(final Clazz clazz) {
    classes.add(clazz);
  }

  public SortedSet<Function> getFunctions() {
    return new TreeSet<>(functions);
  }

  public void addFunction(final Function function) {
    functions.add(function);
  }

  public boolean isHasFileData() {
    return hasFileData;
  }

  public void setHasFileData(final boolean hasFileData) {
    this.hasFileData = hasFileData;
  }

  public String getLanguage() {
    return language;
  }

  public void setLanguage(final String language) {
    this.language = language;
  }

  public String getPackageName() {
    return packageName;
  }

  public void setPackageName(final String packageName) {
    this.packageName = packageName;
  }

  public Set<String> getImportNames() {
    return Set.copyOf(importNames);
  }

  public void setImportNames(final Collection<String> importNames) {
    this.importNames.clear();
    this.importNames.addAll(importNames);
  }

  public String getLastEditor() {
    return lastEditor;
  }

  public void setLastEditor(final String lastEditor) {
    this.lastEditor = lastEditor;
  }

  public int getAddedLines() {
    return addedLines;
  }

  public void setAddedLines(final int addedLines) {
    this.addedLines = addedLines;
  }

  public int getModifiedLines() {
    return modifiedLines;
  }

  public void setModifiedLines(final int modifiedLines) {
    this.modifiedLines = modifiedLines;
  }

  public int getDeletedLines() {
    return deletedLines;
  }

  public void setDeletedLines(final int deletedLines) {
    this.deletedLines = deletedLines;
  }

  public Map<String, Double> getMetrics() {
    return Map.copyOf(metrics);
  }

  public void setMetrics(final Map<String, Double> metrics) {
    this.metrics.putAll(metrics);
  }

  @Override
  public int compareTo(final FileRevision other) {
    return name.compareTo(other.name);
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof final FileRevision otherFile)) {
      return false;
    }

    return id != null && id.equals(otherFile.id);
  }

  @Override
  public int hashCode() {
    return id != null ? id.hashCode() : System.identityHashCode(this);
  }
}
