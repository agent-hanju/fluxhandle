package me.hanju.fluxhandle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.hanju.fluxhandle.exception.FluxHandleException;

/**
 * A common interface for handles that manage streaming data and produce a final
 * result.
 *
 * <p>
 * Implementations process streaming items through delta merging for
 * incremental result construction and notify {@link FluxListener} of events.
 *
 * @param <T> the type of elements being streamed and the built result
 * @see FluxHandle
 * @see DirectHandle
 */
public interface Handle<T> {

  /**
   * Cancels the streaming and notifies the listener.
   *
   * <p>
   * If already completed, this method has no effect.
   * The current accumulated result will still be available via {@link #get()}.
   */
  void cancel();

  /**
   * Returns whether this handle was cancelled.
   *
   * @return {@code true} if cancelled, {@code false} otherwise
   */
  boolean isCancelled();

  /**
   * Returns whether an error occurred during streaming.
   *
   * @return {@code true} if an error occurred, {@code false} otherwise
   */
  boolean isError();

  /**
   * Returns the error that occurred during streaming, if any.
   *
   * @return the error, or {@code null} if no error occurred
   */
  Throwable getError();

  /**
   * Blocks until the stream completes and returns the built result.
   *
   * @return the merged result
   * @throws FluxHandleException if an error occurred during streaming
   */
  T get();

  /**
   * Blocks until the stream completes or the timeout expires, then returns the
   * built result.
   *
   * @param timeout the maximum time to wait
   * @param unit    the time unit of the timeout argument
   * @return the merged result
   * @throws TimeoutException         if the wait timed out
   * @throws IllegalArgumentException if unit is null
   * @throws FluxHandleException      if an error occurred during streaming
   */
  T get(long timeout, TimeUnit unit) throws TimeoutException;
}
