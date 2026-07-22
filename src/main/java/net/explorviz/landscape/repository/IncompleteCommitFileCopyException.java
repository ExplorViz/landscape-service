package net.explorviz.landscape.repository;

/** Raised when unchanged files could not be linked from a parent commit to a child commit. */
public class IncompleteCommitFileCopyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IncompleteCommitFileCopyException(final String message) {
    super(message);
  }
}
