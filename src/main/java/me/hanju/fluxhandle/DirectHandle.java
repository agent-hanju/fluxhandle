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
 * Example usage:
 *
 * <pre>{@code
 * FluxListener<String> listener = item -> System.out.println("received: " + item);
 * DirectHandle<String, String> handle = new DirectHandle<>(assembler, listener);
 *
 * handle.onNext("first");
 * handle.onNext("second");
 * handle.onComplete();
 *
 * String result = handle.get();
 * }</pre>
 *
 * @param <T> the type of elements being streamed
 * @param <R> the type of the built result
 * @see Handle
 * @see FluxHandle
 * @see FluxListener
 */
public class DirectHandle<T, R> implements Handle<T, R> {
  private static final Logger log = LoggerFactory.getLogger(DirectHandle.class);

  private final FluxListener<T> listener;
  private final FluxAssembler<T, R> assembler;
  private final CompletableFuture<R> future = new CompletableFuture<>();

  private Throwable error = null;
  private boolean completed = false;
  private boolean cancelled = false;

  /**
   * Creates a new DirectHandle with the given assembler and listener.
   *
   * @param assembler the assembler for incremental result construction
   * @param listener  the listener to receive streaming events
   * @throws IllegalArgumentException if any parameter is null
   */
  public DirectHandle(
      final FluxAssembler<T, R> assembler,
      final FluxListener<T> listener) {
    if (assembler == null) {
      throw new IllegalArgumentException("assembler cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    }
    this.assembler = assembler;
    this.listener = listener;
  }

  /**
   * Emits an item to the handle.
   *
   * <p>
   * The item will be applied to the assembler and the listener's
   * {@link FluxListener#onNext(Object)} will be called.
   *
   * @param item the item to emit
   */
  public synchronized void onNext(final T item) {
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
        this.future.complete(this.assembler.build());
      } catch (final Exception ex) {
        log.warn("assembler.build failed", ex);
        e.addSuppressed(new FluxAssemblerException("assembler failed while error", ex));
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

  @Override
  public synchronized void cancel() {
    if (this.completed) {
      log.warn("cancel failed. already completed.");
    } else {
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
