package net.explorviz.landscape.util;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import net.explorviz.landscape.proto.CommitData;
import net.explorviz.landscape.proto.ContributorData;
import net.explorviz.landscape.proto.FileData;
import net.explorviz.landscape.proto.StateDataRequest;
import net.explorviz.landscape.proto.TrackableResourceEvent;
import net.explorviz.landscape.repository.IncompleteCommitFileCopyException;

/** Utility class to map Java exceptions to gRPC exceptions. */
public final class GrpcExceptionMapper {

  private GrpcExceptionMapper() {
    // private constructor to prevent instantiation
  }

  /**
   * Maps an exception to a gRPC RuntimeException.
   *
   * @param e the original exception
   * @param contextInfo optional context string for the error message
   * @return StatusRuntimeException suitable for returning in a gRPC Uni or throwing
   */
  public static StatusRuntimeException mapToGrpcException(
      final Exception e, final String contextInfo) {
    final Exception unwrapped = unwrapException(e);
    if (unwrapped instanceof StatusRuntimeException statusRuntimeException) {
      return statusRuntimeException;
    }

    if (unwrapped instanceof IllegalArgumentException) {
      return Status.INVALID_ARGUMENT
          .withCause(unwrapped)
          .withDescription(unwrapped.getMessage())
          .asRuntimeException();
    }

    if (unwrapped instanceof IncompleteCommitFileCopyException) {
      return Status.FAILED_PRECONDITION
          .withCause(unwrapped)
          .withDescription(unwrapped.getMessage())
          .asRuntimeException();
    }

    return Status.CANCELLED
        .withCause(unwrapped)
        .withDescription("Something went wrong: " + contextInfo + " All changes were rolled back.")
        .augmentDescription("Exception details: " + describeException(unwrapped))
        .asRuntimeException();
  }

  public static StatusRuntimeException mapToGrpcException(
      final Exception e, final StateDataRequest stateData) {
    final String contextInfo =
        "Regarding the call to getStateData for the "
            + "landscape with tokenId '"
            + stateData.getLandscapeToken()
            + "' and repository '"
            + stateData.getRepositoryName()
            + "'.";
    return mapToGrpcException(e, contextInfo);
  }

  public static StatusRuntimeException mapToGrpcException(
      final Exception e, final FileData fileData) {
    final String contextInfo =
        "Regarding the call to persistFile for the file with hash '"
            + fileData.getFileHash()
            + "'.";
    return mapToGrpcException(e, contextInfo);
  }

  public static StatusRuntimeException mapToGrpcException(
      final Exception e, final CommitData commitData) {
    final String contextInfo =
        "Regarding the call to persistCommit for the commit with hash '"
            + commitData.getCommitId()
            + "'.";
    return mapToGrpcException(e, contextInfo);
  }

  public static StatusRuntimeException mapToGrpcException(
      final Exception e, final ContributorData contributorData) {
    final String contextInfo =
        "Regarding the call to persistContributor for the contributor"
            + "with name '"
            + contributorData.getGitUsername()
            + "' and email '"
            + contributorData.getEmail()
            + "'.";
    return mapToGrpcException(e, contextInfo);
  }

  public static StatusRuntimeException mapToGrpcException(
      final Exception e, final TrackableResourceEvent trackableResourceEvent) {
    final String contextInfo =
        "Regarding the call to persistTrackableResourceEvent for "
            + "the event with title '"
            + trackableResourceEvent.getTitle()
            + "' and resource id '"
            + trackableResourceEvent.getResourceId()
            + "'.";
    return mapToGrpcException(e, contextInfo);
  }

  private static Exception unwrapException(final Exception e) {
    Exception current = e;
    while (current.getCause() instanceof Exception cause
        && (current instanceof UndeclaredThrowableException
            || current instanceof InvocationTargetException
            || current.getMessage() == null
            || current.getMessage().isBlank())) {
      current = cause;
    }
    return current;
  }

  private static String describeException(final Exception e) {
    if (e.getMessage() != null && !e.getMessage().isBlank()) {
      return e.getMessage();
    }
    if (e instanceof StatusRuntimeException statusRuntimeException
        && statusRuntimeException.getStatus().getDescription() != null) {
      return statusRuntimeException.getStatus().getDescription();
    }
    return e.getClass().getName();
  }
}
