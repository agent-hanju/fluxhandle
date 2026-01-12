package me.hanju.fluxhandle.deltastream.merge;

/**
 * Exception thrown when an error occurs during delta merging operations.
 *
 * <p>
 * This exception is used by the merge package to signal errors during
 * delta application, object building, or custom assemble method invocation.
 */
public final class MergeException extends RuntimeException {

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the detail message
   * @param cause   the cause (may be null)
   */
  public MergeException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
