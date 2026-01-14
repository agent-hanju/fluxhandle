package me.hanju.fluxhandle.deltastream.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * List/Array 필드에서 요소의 인덱스 필드명을 외부에서 지정합니다.
 *
 * <ul>
 * <li>List/Array 이외의 필드에 사용될 경우 조용히 무시됩니다.</li>
 * <li>index 패러미터를 지정하지 않을 경우 기본값으로 "index"가 적용됩니다.</li>
 * <li>지정된 index 필드가 {@code int}/{@code Integer}가 아니면 무시됩니다.</li>
 * <li>요소 객체에 StreamIndex가 설정되어있을 경우 그 설정을 무시합니다.</li>
 * </ul>
 *
 * <pre>
 * public class Response {
 *   &#64;StreamList(index = "seq") // ExternalItem.seq를 인덱스 필드로 사용
 *   private List&ltExternalItem&gt items;
 * }
 * </pre>
 *
 * @see StreamIndex 요소 클래스 내부에서 인덱스 필드 지정
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StreamList {

  /**
   * 인덱스 필드로 사용할 요소 필드명.
   *
   * @return 인덱스 필드명
   */
  String index() default "index";
}
