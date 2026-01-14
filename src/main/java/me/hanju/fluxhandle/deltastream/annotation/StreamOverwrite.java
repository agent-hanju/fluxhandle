package me.hanju.fluxhandle.deltastream.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 기본 동작(String 연결, Number 합산, List 병합 등)을 무시하고 항상 덮어쓰기합니다.
 *
 * <p>
 * {@code null} 값은 무시되며 기존 값이 유지됩니다.
 *
 * <pre>
 * public class Calling {
 *   &#64;StreamOverwrite
 *   private String type; // 덮어쓰기 적용
 *
 *   private String args; // 연결 유지
 * }
 *
 * public class Snapshot {
 *   &#64;StreamOverwrite
 *   private List<Item> items; // 항상 전체 교체
 * }
 * </pre>
 */
@Target({ ElementType.FIELD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
public @interface StreamOverwrite {
}
