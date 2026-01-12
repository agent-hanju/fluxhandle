package me.hanju.fluxhandle.deltastream.map;

import java.util.List;

/**
 * Stateful delta transformer interface.
 *
 * <p>
 * Transforms a stream of deltas of type {@code T} into zero or more deltas of type {@code R}.
 * Unlike stateless {@code Function<T, R>}, implementations can maintain internal state
 * to accumulate information across delta emissions.
 *
 * <p>
 * The return type is {@code List<R>} to support:
 * <ul>
 *   <li>0 outputs - filtering or buffering (return empty list)</li>
 *   <li>1 output - simple 1:1 transformation</li>
 *   <li>N outputs - splitting or expanding deltas</li>
 * </ul>
 *
 * <p>
 * Example usage:
 *
 * <pre>{@code
 * // SDK chunk to custom delta transformation (1:1)
 * DeltaMapper<SdkChunk, MyDelta> mapper = new DeltaMapper<>() {
 *     private final StringBuilder contentBuffer = new StringBuilder();
 *
 *     @Override
 *     public List<MyDelta> map(SdkChunk chunk) {
 *         contentBuffer.append(chunk.getContent());
 *         return List.of(new MyDelta(contentBuffer.toString(), chunk.getIndex()));
 *     }
 * };
 *
 * // Buffering example (0:N)
 * DeltaMapper<String, String> lineMapper = new DeltaMapper<>() {
 *     private final StringBuilder buffer = new StringBuilder();
 *
 *     @Override
 *     public List<String> map(String chunk) {
 *         buffer.append(chunk);
 *         List<String> lines = new ArrayList<>();
 *         int idx;
 *         while ((idx = buffer.indexOf("\n")) >= 0) {
 *             lines.add(buffer.substring(0, idx));
 *             buffer.delete(0, idx + 1);
 *         }
 *         return lines;  // 0개 또는 N개 반환
 *     }
 * };
 *
 * Flux<MyDelta> mapped = sdkFlux.flatMapIterable(mapper::map);
 * }</pre>
 *
 * @param <T> input delta type
 * @param <R> output delta type
 */
@FunctionalInterface
public interface DeltaMapper<T, R> {

  /**
   * Transforms an input delta to zero or more output deltas.
   *
   * <p>
   * Implementations may maintain internal state to accumulate information
   * across multiple invocations.
   *
   * @param delta the input delta
   * @return list of transformed deltas (may be empty)
   */
  List<R> map(T delta);
}
