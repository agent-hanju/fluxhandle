package me.hanju.fluxhandle.exception;

import me.hanju.fluxhandle.FluxListener;

/**
 * 스트리밍 중 {@link FluxListener}에서 에러가 발생할 때 던져지는 예외.
 *
 * <p>
 * 이 예외는 {@link FluxListener#onNext(Object)}, {@link FluxListener#onComplete()},
 * 또는 {@link FluxListener#onCancel()}과 같은 리스너 콜백 메서드에서
 * 발생하는 에러를 래핑한다.
 */
public final class FluxListenerException extends FluxHandleException {

  /**
   * 지정된 메시지와 원인으로 새 예외를 생성한다.
   *
   * @param message 상세 메시지
   * @param e       원인
   */
  public FluxListenerException(String message, Throwable e) {
    super(message, e);
  }
}
