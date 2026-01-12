package me.hanju.fluxhandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.hanju.fluxhandle.deltastream.merge.DeltaMerger;
import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * A simplified wrapper for Project Reactor {@link Flux} that bridges reactive streams
 * to listener-based callbacks with delta merging.
 *
 * <p>
 * SimpleFluxHandle is for cases where the input type and result type are the same
 * ({@code T == T}), meaning no transformation is needed. It subscribes to a {@link Flux}
 * and processes each emitted item by:
 * <ul>
 * <li>Notifying the {@link FluxListener} of the delta</li>
 * <li>Merging the delta directly via {@link DeltaMerger}</li>
 * </ul>
 *
 * <p>
 * For cases where transformation is needed ({@code T -> R}), use {@link FluxHandle} instead.
 *
 * <p>
 * The final result can be retrieved synchronously via {@link #get()} or
 * {@link #get(long, TimeUnit)} after the stream completes.
 *
 * <p>
 * Delta merging is handled automatically based on field types:
 * <ul>
 * <li>String: append (concatenation)</li>
 * <li>Number: sum (addition)</li>
 * <li>Object: recursive merge</li>
 * <li>Primitive List: extend</li>
 * <li>Object List: index-based merge (requires {@code @StreamIndex})</li>
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * Flux<ChatCompletionChunk> flux = ...;
 * SimpleFluxHandle<ChatCompletionChunk> handle = new SimpleFluxHandle<>(
 *     flux,
 *     ChatCompletionChunk.class,
 *     chunk -> System.out.println(chunk)
 * );
 *
 * ChatCompletionChunk result = handle.get();
 * }</pre>
 *
 * @param <T> the type of elements emitted by the Flux and the built result
 * @see IFluxHandle
 * @see FluxHandle
 * @see FluxListener
 */
public class SimpleFluxHandle<T> implements IFluxHandle<T, T> {
  private static final Logger log = LoggerFactory.getLogger(SimpleFluxHandle.class);

  private final FluxListener<T> listener;
  private final Disposable disposable;
  private final DeltaMerger<T> merger;
  private final CompletableFuture<T> future = new CompletableFuture<>();

  private Throwable error = null;
  private boolean completed = false;
  private boolean cancelled = false;

  /**
   * Creates a new SimpleFluxHandle that subscribes to the given Flux.
   *
   * <p>
   * The subscription is performed immediately on a bounded elastic scheduler.
   *
   * @param flux     the reactive stream to subscribe to
   * @param type     the class of the streaming objects
   * @param listener the listener to receive streaming events
   * @throws IllegalArgumentException if any parameter is null
   */
  public SimpleFluxHandle(
      final Flux<T> flux,
      final Class<T> type,
      final FluxListener<T> listener) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    } else if (type == null) {
      throw new IllegalArgumentException("type cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    } else {
      this.merger = new DeltaMerger<>(type);
      this.listener = listener;
      this.disposable = flux.subscribeOn(Schedulers.boundedElastic())
          .subscribe(
              this::onNext,
              this::onError,
              this::onComplete);
    }
  }

  private synchronized void onNext(final T item) {
    if (this.completed) {
      log.warn("emitting next failed. already completed.");
      return;
    }

    // 1. Merge delta directly
    try {
      this.merger.applyDelta(item);
    } catch (final Exception e) {
      this.onError(new FluxHandleException("delta merge failed", e));
      return;
    }

    // 2. Notify listener
    try {
      this.listener.onNext(item);
    } catch (final Exception ex) {
      this.onError(new FluxListenerException("listener failed while emit next", ex));
      return;
    }

    log.debug("emitted: {}", item);
  }

  private synchronized void onError(final Throwable e) {
    log.info("received an error", e);
    if (this.completed) {
      log.warn("emitting error failed. already completed.");
    } else {
      try {
        this.listener.onError(e);
      } catch (final Exception ex) {
        log.warn("listener.onError failed", ex);
        e.addSuppressed(new FluxListenerException("listener failed while error", ex));
      }
      this.error = e;
      this.completed = true;
      try {
        this.future.complete(this.merger.build());
      } catch (final Exception ex) {
        log.warn("merger.build failed", ex);
        e.addSuppressed(new FluxHandleException("merge build failed while error", ex));
        this.future.completeExceptionally(e);
      }
    }
  }

  private synchronized void onComplete() {
    if (this.completed) {
      log.warn("emitting complete failed. already completed.");
    } else {
      final T result;
      try {
        result = this.merger.build();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("merge build failed while complete", e));
        return;
      }
      try {
        this.listener.onComplete();
      } catch (final Exception e) {
        this.onError(new FluxListenerException("listener failed while complete", e));
        return;
      }
      this.future.complete(result);
      this.completed = true;
      log.info("completed");
    }
  }

  /**
   * Cancels the streaming and notifies the listener.
   *
   * <p>
   * If already completed, this method has no effect.
   * The current accumulated result will still be available via {@link #get()}.
   */
  @Override
  public synchronized void cancel() {
    if (this.completed) {
      log.warn("cancel failed. already completed.");
    } else {
      this.disposable.dispose();
      final T result;
      try {
        result = this.merger.build();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("build failed while cancel", e));
        return;
      }
      try {
        this.listener.onCancel();
      } catch (final Exception ex) {
        this.onError(new FluxListenerException("listener failed while cancel", ex));
        return;
      }
      this.cancelled = true;
      this.completed = true;
      this.future.complete(result);
      log.info("cancelled");
    }
  }

  /**
   * Returns whether this handle was cancelled.
   *
   * @return {@code true} if cancelled, {@code false} otherwise
   */
  @Override
  public boolean isCancelled() {
    return this.cancelled;
  }

  /**
   * Returns whether an error occurred during streaming.
   *
   * @return {@code true} if an error occurred, {@code false} otherwise
   */
  @Override
  public boolean isError() {
    return this.error != null;
  }

  /**
   * Returns the error that occurred during streaming, if any.
   *
   * @return the error, or {@code null} if no error occurred
   */
  @Override
  public Throwable getError() {
    return this.error;
  }

  /**
   * Blocks until the stream completes and returns the built result.
   *
   * @return the merged result
   * @throws FluxHandleException if an error occurred during streaming
   */
  @Override
  public T get() {
    try {
      return future.get();
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof final FluxHandleException fhe) {
        throw fhe;
      } else {
        throw new FluxHandleException("unexpected", e.getCause());
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FluxHandleException("interrupted", e);
    }
  }

  /**
   * Blocks until the stream completes or the timeout expires, then returns the built result.
   *
   * @param timeout the maximum time to wait
   * @param unit    the time unit of the timeout argument
   * @return the merged result
   * @throws TimeoutException         if the wait timed out
   * @throws IllegalArgumentException if unit is null
   * @throws FluxHandleException      if an error occurred during streaming
   */
  @Override
  public T get(final long timeout, final TimeUnit unit) throws TimeoutException {
    if (unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
    try {
      return future.get(timeout, unit);
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof final FluxHandleException fhe) {
        throw fhe;
      } else {
        throw new FluxHandleException("unexpected", e.getCause());
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FluxHandleException("interrupted", e);
    }
  }
}
