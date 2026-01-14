package me.hanju.fluxhandle.deltastream.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * List/Array 요소의 병합 규칙에 쓰일 인덱스 필드를 지정합니다.
 *
 * <ul>
 * <li>인덱스 필드의 값이 같은 요소끼리 병합됩니다.</li>
 * <li>인덱스 필드 자체는 덮어쓰기 동작을 합니다.</li>
 * <li>{@code "index"}라는 이름의 필드는 어노테이션 없이도 자동 인식됩니다.</li>
 * <li>{@code int} 또는 {@code Integer} 타입이어야 합니다. 다른 타입이면 무시됩니다.</li>
 * <li>이 어노테이션은 상위 객체에서 StreamList가 설정되었을 경우 무시됩니다.</li>
 * </ul>
 *
 * <pre>
 * public class Calling {
 *   &#64;StreamIndex
 *   private int idx; // 이 필드로 요소 매칭
 *   private String args; // 매칭된 객체끼리 병합 규칙 적용(String의 경우 concat)
 * }
 * </pre>
 *
 * @see StreamList 상위 객체의 리스트 필드에서 인덱스 필드 지정
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StreamIndex {
}
