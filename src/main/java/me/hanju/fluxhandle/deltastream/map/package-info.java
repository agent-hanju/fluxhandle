/**
 * Delta mapping interfaces for transforming delta streams.
 *
 * <p>
 * This package provides the {@link me.hanju.fluxhandle.deltastream.map.DeltaMapper}
 * interface for stateful transformation of delta streams from one type to another.
 *
 * <p>
 * Unlike stateless mapping (e.g., {@code Flux.map()}), {@code DeltaMapper} allows
 * implementations to maintain internal state across delta emissions, which is useful
 * when the transformation depends on previously received deltas.
 *
 * <p>
 * Each invocation returns {@code List<R>} to support 0:N mapping scenarios:
 * filtering, buffering, splitting, or expanding deltas as needed.
 *
 * @see me.hanju.fluxhandle.deltastream.map.DeltaMapper
 */
package me.hanju.fluxhandle.deltastream.map;
