package me.hanju.fluxhandle.deltastream.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field to always be overwritten during delta merging.
 *
 * <p>
 * By default, fields are merged according to their type:
 * <ul>
 *   <li>String: concatenation</li>
 *   <li>Number: addition</li>
 *   <li>Object: recursive merge</li>
 *   <li>List: index-based merge or extend</li>
 * </ul>
 *
 * <p>
 * Fields marked with this annotation bypass these rules and are simply
 * replaced with the delta value.
 *
 * <p>
 * Note: Fields marked with {@link StreamIndex} are automatically treated
 * as overwrite fields without needing this annotation.
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * public class ToolCall {
 *   @StreamIndex
 *   private Integer index;    // automatically overwritten (index field)
 *
 *   @StreamOverwrite
 *   private String type;      // explicitly overwritten (discriminator)
 *
 *   private String arguments; // concatenated (default String behavior)
 * }
 * }</pre>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface StreamOverwrite {
}
