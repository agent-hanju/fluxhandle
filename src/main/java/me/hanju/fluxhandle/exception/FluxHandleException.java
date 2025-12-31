package me.hanju.fluxhandle.exception;

import me.hanju.fluxhandle.FluxHandle;

/**
 * Base exception class for all {@link FluxHandle}-related exceptions.
 *
 * <p>
 * This is an unchecked exception that serves as the parent for more specific
 * exceptions thrown during streaming operations.
 *
 * @see FluxAssemblerException
 * @see FluxListenerException
 */
public class FluxHandleException extends RuntimeException {

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the detail message
   * @param cause   the cause
   */
  public FluxHandleException(String message, Throwable cause) {
    super(message, cause);
  }
}
