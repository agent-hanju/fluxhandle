/**
 * FluxHandle - A lightweight wrapper for Project Reactor Flux.
 *
 * <p>
 * This package provides utilities for bridging reactive streams to
 * listener-based callbacks with incremental result building.
 *
 * <p>
 * Main components:
 * <ul>
 * <li>{@link me.hanju.fluxhandle.Handle} - Common interface for all handle
 * implementations</li>
 * <li>{@link me.hanju.fluxhandle.FluxHandle} - Flux-based wrapper class</li>
 * <li>{@link me.hanju.fluxhandle.DirectHandle} - Direct emission handle without
 * Flux dependency</li>
 * <li>{@link me.hanju.fluxhandle.FluxListener} - Interface for receiving
 * streaming events</li>
 * </ul>
 *
 * <p>
 * Delta merging is automatic: if a class has an {@code assemble(T)} method,
 * it will be used for custom merging. Otherwise, fields are merged automatically
 * using reflection-based rules (String append, Number sum, Object recursive merge,
 * List index-based merge).
 *
 * @see me.hanju.fluxhandle.Handle
 * @see me.hanju.fluxhandle.FluxHandle
 * @see me.hanju.fluxhandle.DirectHandle
 */
package me.hanju.fluxhandle;
