package net.explorviz.landscape.repository;

/** Raised when a child commit requires an analyzed parent that is not yet visible in the graph. */
public class ParentCommitNotReadyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ParentCommitNotReadyException(final String message) {
    super(message);
  }
}
