package net.explorviz.landscape.service.assemble.impl;

import net.explorviz.landscape.service.assemble.LandscapeAssemblyException;

/**
 * Thrown if a records that is to be inserted into the model is invalid.
 */
public class InvalidRecordException extends LandscapeAssemblyException {

  private static final long serialVersionUID = 1L; // NOPMD

  public InvalidRecordException() {
    super();
  }

  public InvalidRecordException(final String message) {
    super(message);
  }
}
