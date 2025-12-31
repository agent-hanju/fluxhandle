/**
 * FluxHandle - A lightweight wrapper for Project Reactor Flux.
 *
 * <p>
 * This package provides utilities for bridging reactive streams to
 * listener-based
 * callbacks with incremental result building.
 *
 * <p>
 * Main components:
 * <ul>
 * <li>{@link me.hanju.fluxhandle.FluxHandle} - The main wrapper class</li>
 * <li>{@link me.hanju.fluxhandle.FluxAssembler} - Interface for incremental
 * result building</li>
 * <li>{@link me.hanju.fluxhandle.FluxListener} - Interface for receiving
 * streaming events</li>
 * </ul>
 *
 * @see me.hanju.fluxhandle.FluxHandle
 */
package me.hanju.fluxhandle;
