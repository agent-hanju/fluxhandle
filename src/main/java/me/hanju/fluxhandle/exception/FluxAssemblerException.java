package me.hanju.fluxhandle.exception;

import me.hanju.fluxhandle.FluxAssembler;

/**
 * Exception thrown when an error occurs in the {@link FluxAssembler} during
 * streaming.
 *
 * <p>
 * This exception wraps errors that occur during
 * {@link FluxAssembler#applyDelta(Object)}
 * or {@link FluxAssembler#build()} operations.
 */
public final class FluxAssemblerException extends FluxHandleException {

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the detail message
   * @param e       the cause
   */
  public FluxAssemblerException(String message, Throwable e) {
    super(message, e);
  }
}
