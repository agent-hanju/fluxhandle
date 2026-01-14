package me.hanju.fluxhandle;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.hanju.fluxhandle.deltastream.map.DeltaMapper;
import me.hanju.fluxhandle.deltastream.merge.DeltaMerger;
import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * A flexible streaming handle that supports both direct emission and Flux subscription,
 * with optional delta transformation and automatic merging.
 *
 * <p>
 * StreamHandle is the core implementation that provides:
 * <ul>
 * <li>Direct emission via {@link #emitNext(Object)}, {@link #emitError(Throwable)}, {@link #emitComplete()}</li>
 * <li>Flux subscription via {@link #subscribe(Flux)} or {@link #subscribe(Flux, DeltaMapper)}</li>
 * <li>Subscription replacement support - can switch to different Flux sources</li>
 * <li>Automatic delta merging via {@link DeltaMerger}</li>
 * </ul>
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
 * Example usage with direct emission:
 *
 * <pre>{@code
 * StreamHandle<String> handle = new StreamHandle<>(String.class, s -> System.out.println(s));
 *
 * handle.emitNext("Hello");
 * handle.emitNext(" World");
 * handle.emitComplete();
 *
 * String result = handle.get();  // "Hello World"
 * }</pre>
 *
 * <p>
 * Example usage with Flux subscription:
 *
 * <pre>{@code
 * StreamHandle<String> handle = new StreamHandle<>(String.class, s -> System.out.println(s));
 * handle.subscribe(Flux.just("Hello", " ", "World"));
 *
 * String result = handle.get();  // "Hello World"
 * }</pre>
 *
 * <p>
 * Example usage with transformation:
 *
 * <pre>{@code
 * StreamHandle<MyDelta> handle = new StreamHandle<>(MyDelta.class, delta -> {});
 *
 * DeltaMapper<SdkChunk, MyDelta> mapper = chunk -> List.of(new MyDelta(chunk.getContent()));
 * handle.subscribe(sdkStream, mapper);
 *
 * MyDelta result = handle.get();
 * }</pre>
 *
 * @param <R> the type of the result and emitted deltas
 * @see Handle
 * @see DeltaMapper
 * @see FluxListener
 */
public class StreamHandle<R> implements Handle<R> {
  private static final Logger log = LoggerFactory.getLogger(StreamHandle.class);

  private final FluxListener<R> listener;
  private final DeltaMerger<R> merger;
  private final CompletableFuture<R> future = new CompletableFuture<>();

  private Disposable disposable = null;
  private DeltaMapper<?, R> currentMapper = null;
  private Throwable error = null;
  private boolean completed = false;
  private boolean cancelled = false;

  /**
   * Creates a new StreamHandle with the given result type and listener.
   *
   * @param resultType the class of the result type
   * @param listener   the listener to receive streaming events
   * @throws IllegalArgumentException if any parameter is null
   */
  public StreamHandle(
      final Class<R> resultType,
      final FluxListener<R> listener) {
    if (resultType == null) {
      throw new IllegalArgumentException("resultType cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    }
    this.merger = new DeltaMerger<>(resultType);
    this.listener = listener;
  }

  /**
   * Subscribes to the given Flux without transformation.
   *
   * <p>
   * If a previous subscription exists, it will be disposed before subscribing to the new Flux.
   * The subscription is performed on a bounded elastic scheduler.
   *
   * @param flux the reactive stream to subscribe to
   * @throws IllegalArgumentException if flux is null
   * @throws IllegalStateException    if the handle is already completed
   */
  public synchronized void subscribe(final Flux<R> flux) {
    subscribe(flux, List::of);
  }

  /**
   * Subscribes to the given Flux with transformation via mapper.
   *
   * <p>
   * If a previous subscription exists, it will be disposed before subscribing to the new Flux.
   * The subscription is performed on a bounded elastic scheduler.
   *
   * <p>
   * Note: If using a stateful mapper and replacing subscriptions, ensure proper state management.
   * Pass a new mapper instance if you want fresh state, or reuse the same mapper to continue accumulating.
   *
   * @param <T>    the type of input elements from the Flux
   * @param flux   the reactive stream to subscribe to
   * @param mapper the delta mapper to transform input deltas to result type
   * @throws IllegalArgumentException if flux or mapper is null
   * @throws IllegalStateException    if the handle is already completed
   */
  public synchronized <T> void subscribe(final Flux<T> flux, final DeltaMapper<T, R> mapper) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    }
    if (mapper == null) {
      throw new IllegalArgumentException("mapper cannot be null");
    }
    if (this.completed) {
      throw new IllegalStateException("cannot subscribe after completion");
    }
    if (this.disposable != null) {
      this.disposable.dispose();
      log.debug("previous subscription disposed");
    }
    this.currentMapper = mapper;
    this.disposable = flux.subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            item -> this.onNext(item, mapper),
            this::onError,
            () -> this.onComplete(mapper));
    log.debug("subscribed to new flux");
  }

  /**
   * Emits a result item directly to the handle.
   *
   * <p>
   * The item will be merged into the accumulated result and the listener's
   * {@link FluxListener#onNext(Object)} will be called.
   *
   * @param item the result item to emit
   */
  public synchronized void emitNext(final R item) {
    if (this.completed) {
      log.warn("emitting next failed. already completed.");
      return;
    }

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

    log.debug("emitted directly: {}", item);
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
  public synchronized void emitError(final Throwable e) {
    this.onError(e);
  }

  /**
   * Completes the handle successfully.
   *
   * <p>
   * The listener's {@link FluxListener#onComplete()} will be called and
   * the result will be available via {@link #get()}.
   */
  public synchronized void emitComplete() {
    if (this.completed) {
      log.warn("emitting complete failed. already completed.");
    } else {
      final R result;
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

  private <T> void onNext(final T item, final DeltaMapper<T, R> mapper) {
    if (this.completed) {
      log.warn("emitting next failed. already completed.");
      return;
    }

    // 1. Transform delta (0:N mapping)
    final List<R> mappedDeltas;
    try {
      mappedDeltas = mapper.map(item);
    } catch (final Exception e) {
      this.onError(new FluxHandleException("delta mapping failed", e));
      return;
    }

    // 2. Merge each transformed delta and notify listener
    for (final R delta : mappedDeltas) {
      try {
        this.merger.applyDelta(delta);
      } catch (final Exception e) {
        this.onError(new FluxHandleException("delta merge failed", e));
        return;
      }

      try {
        this.listener.onNext(delta);
      } catch (final Exception ex) {
        this.onError(new FluxListenerException("listener failed while emit next", ex));
        return;
      }
    }

    log.debug("emitted: {} -> {} mapped", item, mappedDeltas.size());
  }

  private void onError(final Throwable e) {
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

  private <T> void onComplete(final DeltaMapper<T, R> mapper) {
    if (this.completed) {
      log.warn("emitting complete failed. already completed.");
    } else {
      // Flush remaining buffered deltas from mapper
      final List<R> flushedDeltas;
      try {
        flushedDeltas = mapper.flush();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("delta flush failed", e));
        return;
      }

      for (final R delta : flushedDeltas) {
        try {
          this.merger.applyDelta(delta);
        } catch (final Exception e) {
          this.onError(new FluxHandleException("delta merge failed during flush", e));
          return;
        }

        try {
          this.listener.onNext(delta);
        } catch (final Exception ex) {
          this.onError(new FluxListenerException("listener failed during flush", ex));
          return;
        }
      }

      final R result;
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
      if (this.disposable != null) {
        this.disposable.dispose();
      }

      // Flush remaining buffered deltas from mapper if present
      if (this.currentMapper != null) {
        final List<R> flushedDeltas;
        try {
          flushedDeltas = this.currentMapper.flush();
        } catch (final Exception e) {
          this.onError(new FluxHandleException("delta flush failed during cancel", e));
          return;
        }

        for (final R delta : flushedDeltas) {
          try {
            this.merger.applyDelta(delta);
          } catch (final Exception e) {
            this.onError(new FluxHandleException("delta merge failed during cancel flush", e));
            return;
          }

          try {
            this.listener.onNext(delta);
          } catch (final Exception ex) {
            this.onError(new FluxListenerException("listener failed during cancel flush", ex));
            return;
          }
        }
      }

      final R result;
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
   * @return the merged result of type {@code R}
   * @throws FluxHandleException if an error occurred during streaming
   */
  @Override
  public R get() {
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
   * @return the merged result of type {@code R}
   * @throws TimeoutException         if the wait timed out
   * @throws IllegalArgumentException if unit is null
   * @throws FluxHandleException      if an error occurred during streaming
   */
  @Override
  public R get(final long timeout, final TimeUnit unit) throws TimeoutException {
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
