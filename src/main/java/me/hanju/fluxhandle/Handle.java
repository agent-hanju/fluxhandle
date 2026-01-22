package me.hanju.fluxhandle;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import me.hanju.fluxhandle.exception.FluxHandleException;

/**
 * 스트리밍 데이터를 관리하고 최종 결과를 생성하는 핸들의 공통 인터페이스.
 *
 * <p>
 * 구현체는 델타 매핑과 병합을 통해 스트리밍 아이템을 처리하여
 * {@code R} 타입의 결과를 구성한다.
 *
 * <p>
 * {@link FluxListener}는 변환 후 {@code R} 타입의 변환된 델타를 수신한다.
 *
 * @param <R> 빌드된 결과의 타입
 * @see StreamHandle
 * @see FluxHandle
 */
@Deprecated(since = "0.4.2", forRemoval = true)
public interface Handle<R> {

  /**
   * 스트리밍을 취소하고 리스너에 알린다.
   *
   * <p>
   * 이미 완료된 경우 이 메서드는 아무 효과가 없다.
   * 현재까지 누적된 결과는 {@link #get()}을 통해 여전히 사용 가능하다.
   */
  void cancel();

  /**
   * 이 핸들이 취소되었는지 반환한다.
   *
   * @return 취소된 경우 {@code true}, 그렇지 않으면 {@code false}
   */
  boolean isCancelled();

  /**
   * 스트리밍 중 에러가 발생했는지 반환한다.
   *
   * @return 에러가 발생한 경우 {@code true}, 그렇지 않으면 {@code false}
   */
  boolean isError();

  /**
   * 스트리밍 중 발생한 에러를 반환한다 (있는 경우).
   *
   * @return 에러, 또는 에러가 발생하지 않은 경우 {@code null}
   */
  Throwable getError();

  /**
   * 스트림이 완료될 때까지 블로킹하고 빌드된 결과를 반환한다.
   *
   * @return {@code R} 타입의 병합된 결과
   * @throws FluxHandleException 스트리밍 중 에러가 발생한 경우
   */
  R get();

  /**
   * 스트림이 완료되거나 타임아웃이 만료될 때까지 블로킹한 후 빌드된 결과를 반환한다.
   *
   * @param timeout 최대 대기 시간
   * @param unit    타임아웃 인자의 시간 단위
   * @return {@code R} 타입의 병합된 결과
   * @throws TimeoutException         대기 시간이 초과된 경우
   * @throws IllegalArgumentException unit이 null인 경우
   * @throws FluxHandleException      스트리밍 중 에러가 발생한 경우
   */
  R get(long timeout, TimeUnit unit) throws TimeoutException;
}
