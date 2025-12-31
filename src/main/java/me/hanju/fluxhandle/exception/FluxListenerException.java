package me.hanju.fluxhandle.exception;

import me.hanju.fluxhandle.FluxListener;

/**
 * Exception thrown when an error occurs in the {@link FluxListener} during
 * streaming.
 *
 * <p>
 * This exception wraps errors that occur during listener callback methods such
 * as
 * {@link FluxListener#onNext(Object)}, {@link FluxListener#onComplete()},
 * or {@link FluxListener#onCancel()}.
 */
public final class FluxListenerException extends FluxHandleException {

  /**
   * Constructs a new exception with the specified message and cause.
   *
   * @param message the detail message
   * @param e       the cause
   */
  public FluxListenerException(String message, Throwable e) {
    super(message, e);
  }
}
