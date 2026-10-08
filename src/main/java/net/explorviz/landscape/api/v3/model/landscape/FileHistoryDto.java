package net.explorviz.landscape.api.v3.model.landscape;

/**
 * A change to a file in the history of a file.
 *
 * @param commitHash the commit that made the change
 * @param date author date of the commit
 * @param action {@code ADDED}, {@code MODIFIED}, {@code DELETED} or {@link #RENAMED}
 * @param path the path the file had when the change was made, which differs between the changes
 *     before and after a rename
 * @param renamedFrom for a {@link #RENAMED} change the path the file had before, otherwise {@code
 *     null}
 * @param authorName display name of the commit author when known, otherwise {@code null}
 * @param mergeCommit {@code true} when the commit has more than one git parent
 */
public record FileHistoryDto(
    String commitHash,
    long date,
    String action,
    String path,
    String renamedFrom,
    String authorName,
    boolean mergeCommit) {

  /** The file was moved to its current path, possibly with modifications. */
  public static final String RENAMED = "RENAMED";
}
