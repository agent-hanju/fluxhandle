package me.hanju.fluxhandle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.hanju.fluxhandle.exception.FluxAssemblerException;
import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * A wrapper for Project Reactor {@link Flux} that bridges reactive streams to
 * listener-based callbacks with incremental result building.
 *
 * <p>
 * FluxHandle subscribes to a {@link Flux} and processes each emitted item by:
 * <ul>
 * <li>Applying the delta to the {@link FluxAssembler} for incremental result
 * construction</li>
 * <li>Notifying the {@link FluxListener} of streaming events</li>
 * </ul>
 *
 * <p>
 * The final result can be retrieved synchronously via {@link #get()} or
 * {@link #get(long, TimeUnit)} after the stream completes.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * Flux<String> flux = ...;
 * FluxHandle<String, String> handle = new FluxHandle<>(flux, myAssembler, myListener);
 *
 * // Wait for completion and get the result
 * String result = handle.get();
 *
 * // Or cancel the stream
 * handle.cancel();
 * }</pre>
 *
 * @param <T> the type of elements emitted by the Flux
 * @param <R> the type of the built result
 * @see FluxAssembler
 * @see FluxListener
 */
public class FluxHandle<T, R> {
  private static final Logger log = LoggerFactory.getLogger(FluxHandle.class);

  private final FluxListener<T> listener;
  private final Disposable disposable;

  private final FluxAssembler<T, R> assembler;
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
   * @param flux      the reactive stream to subscribe to
   * @param assembler the assembler for incremental result construction
   * @param listener  the listener to receive streaming events
   * @throws IllegalArgumentException if any parameter is null
   */
  public FluxHandle(
      final Flux<T> flux,
      final FluxAssembler<T, R> assembler,
      final FluxListener<T> listener) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    } else if (assembler == null) {
      throw new IllegalArgumentException("assembler cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    } else {
      this.assembler = assembler;
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
    } else {
      try {
        this.assembler.applyDelta(item);
      } catch (final Exception e) {
        this.onError(new FluxAssemblerException("applyDelta failed", e));
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
        this.future.complete(this.assembler.build());
      } catch (final Exception ex) {
        log.warn("assembler.build failed", ex);
        e.addSuppressed(new FluxAssemblerException("assembler failed while error", ex));
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
        result = this.assembler.build();
      } catch (final Exception e) {
        this.onError(new FluxAssemblerException("assembler failed while complete", e));
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
   * The current accumulated result from the assembler will still be available via
   * {@link #get()}.
   */
  public synchronized void cancel() {
    if (this.completed) {
      log.warn("cancel failed. already completed.");
    } else {
      this.disposable.dispose();
      final R result;
      try {
        result = this.assembler.build();
      } catch (final Exception e) {
        this.onError(new FluxAssemblerException("build failed while cancel", e));
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
  public boolean isCancelled() {
    return this.cancelled;
  }

  /**
   * Returns whether an error occurred during streaming.
   *
   * @return {@code true} if an error occurred, {@code false} otherwise
   */
  public boolean isError() {
    return this.error != null;
  }

  /**
   * Returns the error that occurred during streaming, if any.
   *
   * @return the error, or {@code null} if no error occurred
   */
  public Throwable getError() {
    return this.error;
  }

  /**
   * Blocks until the stream completes and returns the built result.
   *
   * @return the result built by the {@link FluxAssembler}
   * @throws FluxHandleException if an error occurred during streaming
   */
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
   * Blocks until the stream completes or the timeout expires, then returns the
   * built result.
   *
   * @param timeout the maximum time to wait
   * @param unit    the time unit of the timeout argument
   * @return the result built by the {@link FluxAssembler}
   * @throws TimeoutException         if the wait timed out
   * @throws IllegalArgumentException if unit is null
   * @throws FluxHandleException      if an error occurred during streaming
   */
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
