package me.hanju.fluxhandle.deltastream.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field as the index identifier for list item merging.
 *
 * <p>
 * This annotation is used when the index field has a name other than "index".
 * Fields named "index" are automatically recognized without this annotation.
 *
 * <p>
 * When delta objects arrive with list items, items are matched by their index
 * value and merged together.
 *
 * <p>
 * The annotated field must be of {@code Integer} type.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * // No annotation needed - "index" field is auto-detected
 * public class Choice {
 *   private Integer index;  // automatically recognized
 *   private String content;
 * }
 *
 * // Annotation required - field name is not "index"
 * public class ToolCall {
 *   @StreamIndex
 *   private Integer idx;  // custom name, needs annotation
 *   private String id;
 * }
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StreamIndex {
}
