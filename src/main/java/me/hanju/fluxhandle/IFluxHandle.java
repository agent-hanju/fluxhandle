package me.hanju.fluxhandle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.hanju.fluxhandle.exception.FluxHandleException;

/**
 * A common interface for handles that manage streaming data and produce a final result.
 *
 * <p>
 * Implementations process streaming items of type {@code T} through delta mapping
 * and merging to construct a result of type {@code R}.
 *
 * <p>
 * The {@link FluxListener} receives original deltas of type {@code T} before transformation.
 *
 * @param <T> the type of input elements being streamed
 * @param <R> the type of the built result
 * @see FluxHandle
 * @see SimpleFluxHandle
 */
public interface IFluxHandle<T, R> {

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
   * @return the merged result of type {@code R}
   * @throws FluxHandleException if an error occurred during streaming
   */
  R get();

  /**
   * Blocks until the stream completes or the timeout expires, then returns the built result.
   *
   * @param timeout the maximum time to wait
   * @param unit    the time unit of the timeout argument
   * @return the merged result of type {@code R}
   * @throws TimeoutException         if the wait timed out
   * @throws IllegalArgumentException if unit is null
   * @throws FluxHandleException      if an error occurred during streaming
   */
  R get(long timeout, TimeUnit unit) throws TimeoutException;
}
