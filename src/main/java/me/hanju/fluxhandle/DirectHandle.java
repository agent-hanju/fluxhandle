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

/**
 * A handle that allows direct emission of items while using the same
 * {@link FluxListener} pattern as {@link FluxHandle}.
 *
 * <p>
 * Unlike {@link FluxHandle} which subscribes to a
 * {@link reactor.core.publisher.Flux},
 * DirectHandle allows external code to directly emit items, errors, and
 * completion signals through public {@link #onNext(Object)},
 * {@link #onError(Throwable)}, and {@link #onComplete()} methods.
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
 * Alternatively, if the target class has a {@code merge(T)} method,
 * that method will be used for custom merging logic.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * FluxListener<ChatCompletionChunk> listener = chunk -> System.out.println("received: " + chunk);
 * DirectHandle<ChatCompletionChunk> handle = new DirectHandle<>(ChatCompletionChunk.class, listener);
 *
 * handle.onNext(chunk1);
 * handle.onNext(chunk2);
 * handle.onComplete();
 *
 * ChatCompletionChunk result = handle.get();
 * }</pre>
 *
 * @param <T> the type of elements being streamed and the built result
 * @see Handle
 * @see FluxHandle
 * @see FluxListener
 */
public class DirectHandle<T> implements Handle<T> {
  private static final Logger log = LoggerFactory.getLogger(DirectHandle.class);

  private final FluxListener<T> listener;
  private final DeltaMerger<T> merger;
  private final CompletableFuture<T> future = new CompletableFuture<>();

  private Throwable error = null;
  private boolean completed = false;
  private boolean cancelled = false;

  /**
   * Creates a new DirectHandle with the given type and listener.
   *
   * @param type     the class of the streaming objects
   * @param listener the listener to receive streaming events
   * @throws IllegalArgumentException if any parameter is null
   */
  public DirectHandle(
      final Class<T> type,
      final FluxListener<T> listener) {
    if (type == null) {
      throw new IllegalArgumentException("type cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    }
    this.merger = new DeltaMerger<>(type);
    this.listener = listener;
  }

  /**
   * Emits an item to the handle.
   *
   * <p>
   * The item will be merged into the accumulated result and the listener's
   * {@link FluxListener#onNext(Object)} will be called.
   *
   * @param item the item to emit
   */
  public synchronized void onNext(final T item) {
    if (this.completed) {
      log.warn("emitting next failed. already completed.");
    } else {
      try {
        this.merger.applyDelta(item);
      } catch (final Exception e) {
        this.onError(new FluxHandleException("delta merge failed", e));
        return;
      }
      try {
        this.listener.onNext(item);
      } catch (final Exception ex) {
        this.onError(new FluxListenerException("listener failed while emit next", ex));
        return;
      }
      log.debug("emitted: {}", item);
    }
  }

  /**
   * Emits an error to the handle.
   *
   * <p>
   * The listener's {@link FluxListener#onError(Throwable)} will be called and
   * the handle will be marked as completed.
   *
   * @param e the error to emit
   */
  public synchronized void onError(final Throwable e) {
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

  /**
   * Completes the handle successfully.
   *
   * <p>
   * The listener's {@link FluxListener#onComplete()} will be called and
   * the result will be available via {@link #get()}.
   */
  public synchronized void onComplete() {
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

  @Override
  public synchronized void cancel() {
    if (this.completed) {
      log.warn("cancel failed. already completed.");
    } else {
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

  @Override
  public boolean isCancelled() {
    return this.cancelled;
  }

  @Override
  public boolean isError() {
    return this.error != null;
  }

  @Override
  public Throwable getError() {
    return this.error;
  }

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
