package me.hanju.fluxhandle;

/**
 * An assembler interface for incrementally constructing a result from streaming
 * data.
 *
 * <p>
 * Implementations accumulate deltas received from a
 * {@link reactor.core.publisher.Flux}
 * and produce a final result when {@link #build()} is called.
 *
 * @param <T> the type of items emitted by the Flux
 * @param <R> the type of the built result
 * @see FluxHandle
 */
public interface FluxAssembler<T, R> {

  /**
   * Applies an incremental delta to the current state.
   *
   * <p>
   * This method is called for each item emitted by the Flux.
   *
   * @param delta the incremental data to apply
   */
  void applyDelta(T delta);

  /**
   * Builds and returns the final result from the accumulated state.
   *
   * @return the built result
   */
  R build();
}
