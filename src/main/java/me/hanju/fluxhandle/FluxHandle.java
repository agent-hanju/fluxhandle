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
 * A wrapper for Project Reactor {@link Flux} that bridges reactive streams to
 * listener-based callbacks with delta transformation and merging.
 *
 * <p>
 * FluxHandle subscribes to a {@link Flux} of type {@code T} and processes each emitted item by:
 * <ul>
 * <li>Transforming the delta via {@link DeltaMapper} (0:N mapping)</li>
 * <li>Merging transformed deltas via {@link DeltaMerger}</li>
 * <li>Notifying the {@link FluxListener} of each transformed delta</li>
 * </ul>
 *
 * <p>
 * The final result of type {@code R} can be retrieved synchronously via {@link #get()} or
 * {@link #get(long, TimeUnit)} after the stream completes.
 *
 * <p>
 * For simple cases where {@code T == R} (no transformation needed),
 * use {@link SimpleFluxHandle} instead.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * // Transform SDK chunks to domain objects
 * DeltaMapper<SdkChunk, MyDelta> mapper = chunk ->
 *     List.of(new MyDelta(chunk.getContent(), chunk.getIndex()));
 *
 * FluxHandle<SdkChunk, MyDelta> handle = new FluxHandle<>(
 *     flux,
 *     mapper,
 *     MyDelta.class,
 *     delta -> System.out.println("Transformed delta: " + delta)
 * );
 *
 * MyDelta result = handle.get();
 * }</pre>
 *
 * @param <T> the type of input elements emitted by the Flux
 * @param <R> the type of the transformed and merged result
 * @see IFluxHandle
 * @see SimpleFluxHandle
 * @see DeltaMapper
 * @see FluxListener
 */
public class FluxHandle<T, R> implements IFluxHandle<T, R> {
  private static final Logger log = LoggerFactory.getLogger(FluxHandle.class);

  private final FluxListener<R> listener;
  private final Disposable disposable;
  private final DeltaMapper<T, R> mapper;
  private final DeltaMerger<R> merger;
  private final CompletableFuture<R> future = new CompletableFuture<>();

  private Throwable error = null;
  private boolean completed = false;
  private boolean cancelled = false;

  /**
   * Creates a new FluxHandle that subscribes to the given Flux.
   *
   * <p>
   * The subscription is performed immediately on a bounded elastic scheduler.
   *
   * @param flux       the reactive stream to subscribe to
   * @param mapper     the delta mapper to transform input deltas
   * @param resultType the class of the result type
   * @param listener   the listener to receive streaming events
   * @throws IllegalArgumentException if any parameter is null
   */
  public FluxHandle(
      final Flux<T> flux,
      final DeltaMapper<T, R> mapper,
      final Class<R> resultType,
      final FluxListener<R> listener) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    } else if (mapper == null) {
      throw new IllegalArgumentException("mapper cannot be null");
    } else if (resultType == null) {
      throw new IllegalArgumentException("resultType cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    } else {
      this.mapper = mapper;
      this.merger = new DeltaMerger<>(resultType);
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

    // 1. Transform delta (0:N mapping)
    final List<R> mappedDeltas;
    try {
      mappedDeltas = this.mapper.map(item);
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
      this.disposable.dispose();
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
