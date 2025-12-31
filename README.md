# FluxHandle

A lightweight wrapper for Project Reactor Flux that bridges reactive streams to listener-based callbacks with incremental result building.

## Features

- Subscribe to `Flux` streams with listener-based callbacks
- Incrementally build results using `FluxAssembler<T, R>`
- Synchronous result retrieval with `get()` or `get(timeout, unit)`
- Cancellation support with `cancel()`
- Exception handling with `FluxHandleException` hierarchy

## Installation

### JitPack

Add the JitPack repository:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add the dependency:

```groovy
dependencies {
    implementation 'com.github.agent-hanju:fluxhandle:0.1.0'
}
```

## Usage

```java
Flux<String> flux = Flux.just("Hello", " ", "World");

FluxHandle<String, String> handle = new FluxHandle<>(
    flux,
    new FluxAssembler<String, String>() {
        private final StringBuilder sb = new StringBuilder();

        @Override
        public void applyDelta(String delta) {
            sb.append(delta);
        }

        @Override
        public String build() {
            return sb.toString();
        }
    },
    item -> System.out.println("Received: " + item)
);

// Block and get result
String result = handle.get();
System.out.println(result); // "Hello World"

// Or with timeout
String result = handle.get(5, TimeUnit.SECONDS);

// Or cancel
handle.cancel();
```

## Components

### FluxHandle<T, R>

The main class that wraps a `Flux<T>` and manages the streaming lifecycle, building a result of type `R`.

### FluxAssembler<T, R>

Interface for incrementally building a result of type `R` from streamed items of type `T`:

- `applyDelta(T delta)` - Apply incremental updates
- `build()` - Build and return the final result

### FluxListener<T>

Interface for receiving streaming events:

- `onNext(T item)` - Called for each emitted item
- `onError(Throwable e)` - Called on error (default: logs warning)
- `onComplete()` - Called on successful completion
- `onCancel()` - Called when cancelled

## Design Philosophy: Partial Results on Error/Cancel

**Important:** Unlike typical `Future` implementations, `FluxHandle`'s internal `CompletableFuture` **always completes normally** with the partial result built up to that point, even when an error occurs or the stream is cancelled.

### Why This Design?

In many real-world streaming scenarios (e.g., AI chat responses, large file downloads, real-time data feeds), users often want to **preserve partial results** even when the stream is interrupted:

- A chat response that was 80% complete before a network error
- Downloaded data that was partially received before cancellation
- Streamed analytics that were collected before timeout

### Behavior Summary

| Event | `get()` Returns | `isError()` | `isCancelled()` |
|-------|-----------------|-------------|-----------------|
| Normal completion | Full result | `false` | `false` |
| Error during stream | **Partial result** | `true` | `false` |
| Manual `cancel()` | **Partial result** | `false` | `true` |

### Example

```java
Flux<String> flux = Flux.concat(
    Flux.just("Hello", " "),
    Flux.error(new RuntimeException("Network error"))
);

FluxHandle<String, String> handle = new FluxHandle<>(flux, assembler, listener);

String result = handle.get();  // Returns "Hello " (partial result)
handle.isError();              // true
handle.getError();             // RuntimeException: Network error
```

### Checking for Errors

Always check `isError()` or `isCancelled()` after `get()` if you need to distinguish between complete and partial results:

```java
String result = handle.get();
if (handle.isError()) {
    log.warn("Partial result due to error: {}", handle.getError().getMessage());
}
```

## Exception Handling

All exceptions extend `FluxHandleException`:

- `FluxAssemblerException` - Errors in assembler operations
- `FluxListenerException` - Errors in listener callbacks

## Requirements

- Java 21+
- Project Reactor Core

## License

MIT License - see [LICENSE](LICENSE)
