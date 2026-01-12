package me.hanju.fluxhandle.deltastream.metadata;

/**
 * Exception thrown when an error occurs during reflection-based metadata operations.
 *
 * <p>
 * This exception is used internally by the metadata package to signal
 * errors during field access, type analysis, or metadata extraction.
 */
public final class MetadataException extends RuntimeException {

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the detail message
   * @param cause   the cause
   */
  public MetadataException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
