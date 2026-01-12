/**
 * FluxHandle - A lightweight wrapper for Project Reactor Flux.
 *
 * <p>
 * This package provides utilities for bridging reactive streams to
 * listener-based callbacks with delta transformation and merging.
 *
 * <p>
 * Main components:
 * <ul>
 * <li>{@link me.hanju.fluxhandle.IFluxHandle} - Common interface for all handle
 * implementations</li>
 * <li>{@link me.hanju.fluxhandle.FluxHandle} - Flux-based wrapper with delta transformation
 * (T to R mapping)</li>
 * <li>{@link me.hanju.fluxhandle.SimpleFluxHandle} - Simplified Flux-based wrapper
 * for same-type merging (T to T)</li>
 * <li>{@link me.hanju.fluxhandle.FluxListener} - Interface for receiving
 * streaming events</li>
 * </ul>
 *
 * <p>
 * Delta merging is automatic: if a class has a {@code merge(T)} method,
 * it will be used for custom merging. Otherwise, fields are merged automatically
 * using reflection-based rules (String append, Number sum, Object recursive merge,
 * List index-based merge).
 *
 * @see me.hanju.fluxhandle.IFluxHandle
 * @see me.hanju.fluxhandle.FluxHandle
 * @see me.hanju.fluxhandle.SimpleFluxHandle
 */
package me.hanju.fluxhandle;
