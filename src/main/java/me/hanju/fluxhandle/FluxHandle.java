package me.hanju.fluxhandle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.hanju.streambind.map.StreamMapper;
import reactor.core.publisher.Flux;

/**
 * 생성 시 즉시 Flux를 구독하는 {@link StreamHandle}의 편의 래퍼.
 *
 * <p>
 * FluxHandle은 선택적 변환과 함께 리액티브 스트림을 처리하는 간단한 방법을 제공한다.
 * 모든 기능을 {@link StreamHandle}에 위임하고 자동으로 구독한다.
 *
 * <p>
 * 더 많은 유연성(지연 구독, 구독 교체, 직접 방출)이 필요하면
 * {@link StreamHandle}을 직접 사용하라.
 *
 * <p>
 * 변환을 사용한 예시:
 *
 * <pre>{@code
 * StreamMapper<SdkChunk, MyDelta> mapper = chunk ->
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
 * 변환 없이 사용한 예시:
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
 * @param <R> 변환 및 병합된 결과의 타입
 * @see Handle
 * @see StreamHandle
 * @see StreamMapper
 * @see FluxListener
 * @deprecated {@link StreamHandle}을 직접 사용하라.
 */
@Deprecated(since = "0.4.2", forRemoval = true)
public class FluxHandle<R> implements Handle<R> {

  private final StreamHandle<R> delegate;

  /**
   * 변환을 사용하여 새 FluxHandle을 생성하고 즉시 구독한다.
   *
   * @param <T>        Flux에서 방출되는 입력 요소의 타입
   * @param <R>        변환 및 병합된 결과의 타입
   * @param flux       구독할 리액티브 스트림
   * @param mapper     입력 델타를 변환하는 델타 매퍼
   * @param resultType 결과 타입의 클래스
   * @param listener   스트리밍 이벤트를 수신할 리스너
   * @return 새 FluxHandle 인스턴스
   * @throws IllegalArgumentException 파라미터 중 하나라도 null인 경우
   */
  public static <T, R> FluxHandle<R> of(
      final Flux<T> flux,
      final StreamMapper<T, R> mapper,
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
   * 변환 없이 새 FluxHandle을 생성하고 즉시 구독한다.
   *
   * <p>
   * 입력 타입과 결과 타입이 동일하고 변환이 필요 없을 때 이 팩토리 메서드를 사용하라.
   *
   * @param <R>        입력이자 결과인 타입
   * @param flux       구독할 리액티브 스트림
   * @param resultType 결과 타입의 클래스 (입력 타입과 동일)
   * @param listener   스트리밍 이벤트를 수신할 리스너
   * @return 새 FluxHandle 인스턴스
   * @throws IllegalArgumentException 파라미터 중 하나라도 null인 경우
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
   * 정적 팩토리 메서드를 위한 private 생성자.
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
