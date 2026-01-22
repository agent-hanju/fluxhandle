package me.hanju.fluxhandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link FluxHandle}에서 스트리밍 이벤트를 수신하는 리스너 인터페이스.
 *
 * <p>
 * 구현체는 각 방출된 아이템, 에러, 완료, 취소에 반응할 수 있다.
 * {@link #onNext(Object)}를 제외한 모든 메서드에 대해 기본 구현이 제공된다.
 *
 * @param <T> 스트리밍되는 아이템의 타입
 * @see FluxHandle
 */
public interface FluxListener<T> {

  /** 기본 메서드 구현에서 사용하는 로거. */
  Logger log = LoggerFactory.getLogger(FluxListener.class);

  /**
   * 스트림에서 새 아이템이 방출될 때 호출된다.
   *
   * @param item 방출된 아이템
   */
  void onNext(T item);

  /**
   * 스트리밍 중 에러가 발생할 때 호출된다.
   *
   * <p>
   * 기본 구현은 warn 레벨로 에러를 로깅한다.
   *
   * @param e 발생한 에러
   */
  default void onError(Throwable e) {
    log.warn("unhandled error while listening", e);
  }

  /**
   * 스트림이 정상적으로 완료될 때 호출된다.
   *
   * <p>
   * 기본 구현은 debug 레벨로 로깅한다.
   */
  default void onComplete() {
    log.debug("completed");
  }

  /**
   * {@link FluxHandle#cancel()}을 통해 스트림이 취소될 때 호출된다.
   *
   * <p>
   * 기본 구현은 debug 레벨로 로깅한다.
   */
  default void onCancel() {
    log.debug("cancelled");
  }
}
