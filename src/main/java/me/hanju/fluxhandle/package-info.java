/**
 * FluxHandle - A lightweight streaming toolkit for Project Reactor Flux.
 *
 * <p>
 * This package provides utilities for bridging reactive streams to
 * listener-based callbacks with delta transformation and merging.
 *
 * <p>
 * Main components:
 * <ul>
 * <li>{@link me.hanju.fluxhandle.Handle} - Common interface for all handle implementations</li>
 * <li>{@link me.hanju.fluxhandle.StreamHandle} - Core handle supporting both direct emission
 * and Flux subscription with deferred subscription support</li>
 * <li>{@link me.hanju.fluxhandle.FluxHandle} - Convenience wrapper that subscribes immediately
 * upon creation</li>
 * <li>{@link me.hanju.fluxhandle.FluxListener} - Interface for receiving streaming events</li>
 * </ul>
 *
 * <p>
 * Delta merging is automatic: if a class has a {@code merge(T)} method,
 * it will be used for custom merging. Otherwise, fields are merged automatically
 * using reflection-based rules (String append, Number sum, Object recursive merge,
 * List index-based merge).
 *
 * @see me.hanju.fluxhandle.Handle
 * @see me.hanju.fluxhandle.StreamHandle
 * @see me.hanju.fluxhandle.FluxHandle
 */
package me.hanju.fluxhandle;
