package me.hanju.fluxhandle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.hanju.fluxhandle.deltastream.map.DeltaMapper;
import reactor.core.publisher.Flux;

/**
 * A convenience wrapper around {@link StreamHandle} that subscribes to a Flux immediately upon creation.
 *
 * <p>
 * FluxHandle provides a simple way to process a reactive stream with optional transformation.
 * It delegates all functionality to {@link StreamHandle} and subscribes automatically.
 *
 * <p>
 * For more flexibility (deferred subscription, subscription replacement, direct emission),
 * use {@link StreamHandle} directly.
 *
 * <p>
 * Example usage with transformation:
 *
 * <pre>{@code
 * DeltaMapper<SdkChunk, MyDelta> mapper = chunk ->
 *     List.of(new MyDelta(chunk.getContent(), chunk.getIndex()));
 *
 * FluxHandle<MyDelta> handle = FluxHandle.of(
 *     sdkStream,
 *     mapper,
 *     MyDelta.class,
 *     delta -> System.out.println("Transformed delta: " + delta)
 * );
 *
 * MyDelta result = handle.get();
 * }</pre>
 *
 * <p>
 * Example usage without transformation:
 *
 * <pre>{@code
 * FluxHandle<String> handle = FluxHandle.of(
 *     Flux.just("Hello", " ", "World"),
 *     String.class,
 *     s -> System.out.println(s)
 * );
 *
 * String result = handle.get();  // "Hello World"
 * }</pre>
 *
 * @param <R> the type of the transformed and merged result
 * @see Handle
 * @see StreamHandle
 * @see DeltaMapper
 * @see FluxListener
 */
public class FluxHandle<R> implements Handle<R> {

  private final StreamHandle<R> delegate;

  /**
   * Creates a new FluxHandle with transformation and subscribes immediately.
   *
   * @param <T>        the type of input elements emitted by the Flux
   * @param flux       the reactive stream to subscribe to
   * @param mapper     the delta mapper to transform input deltas
   * @param resultType the class of the result type
   * @param listener   the listener to receive streaming events
   * @return a new FluxHandle instance
   * @throws IllegalArgumentException if any parameter is null
   */
  public static <T, R> FluxHandle<R> of(
      final Flux<T> flux,
      final DeltaMapper<T, R> mapper,
      final Class<R> resultType,
      final FluxListener<R> listener) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    }
    if (mapper == null) {
      throw new IllegalArgumentException("mapper cannot be null");
    }
    final StreamHandle<R> delegate = new StreamHandle<>(resultType, listener);
    delegate.subscribe(flux, mapper);
    return new FluxHandle<>(delegate);
  }

  /**
   * Creates a new FluxHandle without transformation and subscribes immediately.
   *
   * <p>
   * Use this factory method when the input type and result type are the same
   * and no transformation is needed.
   *
   * @param <R>        the type that is both input and result
   * @param flux       the reactive stream to subscribe to
   * @param resultType the class of the result type (same as input type)
   * @param listener   the listener to receive streaming events
   * @return a new FluxHandle instance
   * @throws IllegalArgumentException if any parameter is null
   */
  public static <R> FluxHandle<R> of(
      final Flux<R> flux,
      final Class<R> resultType,
      final FluxListener<R> listener) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    }
    final StreamHandle<R> delegate = new StreamHandle<>(resultType, listener);
    delegate.subscribe(flux);
    return new FluxHandle<>(delegate);
  }

  /**
   * Private constructor for static factory method.
   */
  private FluxHandle(final StreamHandle<R> delegate) {
    this.delegate = delegate;
  }

  @Override
  public void cancel() {
    this.delegate.cancel();
  }

  @Override
  public boolean isCancelled() {
    return this.delegate.isCancelled();
  }

  @Override
  public boolean isError() {
    return this.delegate.isError();
  }

  @Override
  public Throwable getError() {
    return this.delegate.getError();
  }

  @Override
  public R get() {
    return this.delegate.get();
  }

  @Override
  public R get(final long timeout, final TimeUnit unit) throws TimeoutException {
    return this.delegate.get(timeout, unit);
  }
}
