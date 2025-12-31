package me.hanju.fluxhandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A listener interface for receiving streaming events from a
 * {@link FluxHandle}.
 *
 * <p>
 * Implementations can react to each emitted item, errors, completion, and
 * cancellation.
 * Default implementations are provided for all methods except
 * {@link #onNext(Object)}.
 *
 * @param <T> the type of items being streamed
 * @see FluxHandle
 */
public interface FluxListener<T> {

  Logger log = LoggerFactory.getLogger(FluxListener.class);

  /**
   * Called when a new item is emitted from the stream.
   *
   * @param item the emitted item
   */
  void onNext(T item);

  /**
   * Called when an error occurs during streaming.
   *
   * <p>
   * The default implementation logs the error at warn level.
   *
   * @param e the error that occurred
   */
  default void onError(Throwable e) {
    log.warn("unhandled error while listening", e);
  }

  /**
   * Called when the stream completes successfully.
   *
   * <p>
   * The default implementation logs at debug level.
   */
  default void onComplete() {
    log.debug("completed");
  }

  /**
   * Called when the stream is cancelled via {@link FluxHandle#cancel()}.
   *
   * <p>
   * The default implementation logs at debug level.
   */
  default void onCancel() {
    log.debug("cancelled");
  }
}
