package me.hanju.fluxhandle.exception;

import me.hanju.fluxhandle.FluxHandle;

/**
 * 모든 {@link FluxHandle} 관련 예외의 기본 예외 클래스.
 *
 * <p>
 * 델타 병합과 결과 빌드를 포함한 스트리밍 작업 중에 발생하는
 * 비검사 예외(unchecked exception)이다.
 *
 * @see FluxListenerException
 */
public class FluxHandleException extends RuntimeException {

  /**
   * 지정된 메시지와 원인으로 새 예외를 생성한다.
   *
   * @param message 상세 메시지
   * @param cause   원인
   */
  public FluxHandleException(String message, Throwable cause) {
    super(message, cause);
  }
}
