package me.hanju.fluxhandle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import me.hanju.fluxhandle.exception.FluxAssemblerException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import reactor.core.publisher.Flux;

class FluxHandleTest {

  static class StringBuilderAdapter implements FluxAssembler<String, String> {
    private final StringBuilder sb = new StringBuilder();

    @Override
    public void applyDelta(String delta) {
      sb.append(delta);
    }

    @Override
    public String build() {
      return sb.toString();
    }
  }

  static class RecordingListener implements FluxListener<String> {
    final List<String> items = new ArrayList<>();
    final AtomicBoolean completed = new AtomicBoolean(false);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onNext(String item) {
      items.add(item);
    }

    @Override
    public void onComplete() {
      completed.set(true);
    }

    @Override
    public void onCancel() {
      cancelled.set(true);
    }

    @Override
    public void onError(Throwable e) {
      error.set(e);
    }
  }

  @Test
  void constructor_shouldThrowOnNullArguments() {
    assertThrows(IllegalArgumentException.class, () ->
        new FluxHandle<>(null, new StringBuilderAdapter(), item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        new FluxHandle<>(Flux.just("a"), null, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        new FluxHandle<>(Flux.just("a"), new StringBuilderAdapter(), null));
  }

  @Test
  void get_shouldReturnBuiltResult() {
    Flux<String> flux = Flux.just("a", "b", "c");
    RecordingListener listener = new RecordingListener();

    FluxHandle<String, String> handle = new FluxHandle<>(flux, new StringBuilderAdapter(), listener);
    String result = handle.get();

    assertEquals("abc", result);
    assertEquals(List.of("a", "b", "c"), listener.items);
    assertTrue(listener.completed.get());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
    assertNull(handle.getError());
  }

  @Test
  void getWithTimeout_shouldReturnBuiltResult() throws TimeoutException {
    Flux<String> flux = Flux.just("x", "y");
    FluxHandle<String, String> handle = new FluxHandle<>(flux, new StringBuilderAdapter(), item -> {});

    assertEquals("xy", handle.get(5, TimeUnit.SECONDS));
  }

  @Test
  void getWithTimeout_shouldThrowOnNullUnit() {
    FluxHandle<String, String> handle = new FluxHandle<>(Flux.just("a"), new StringBuilderAdapter(), item -> {});
    assertThrows(IllegalArgumentException.class, () -> handle.get(1, null));
  }

  @Test
  void getWithTimeout_shouldThrowTimeoutException() {
    FluxHandle<String, String> handle = new FluxHandle<>(Flux.never(), new StringBuilderAdapter(), item -> {});
    assertThrows(TimeoutException.class, () -> handle.get(100, TimeUnit.MILLISECONDS));
  }

  @Test
  void cancel_shouldStopStreamAndReturnPartialResult() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Flux<String> flux = Flux.interval(Duration.ofMillis(50))
        .map(i -> "item" + i)
        .doOnCancel(latch::countDown);

    RecordingListener listener = new RecordingListener();
    FluxHandle<String, String> handle = new FluxHandle<>(flux, new StringBuilderAdapter(), listener);

    Thread.sleep(120);
    handle.cancel();

    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertTrue(handle.isCancelled());
    assertTrue(listener.cancelled.get());
    assertFalse(listener.items.isEmpty());
  }

  @Test
  void cancel_afterCompleteShouldHaveNoEffect() {
    RecordingListener listener = new RecordingListener();
    FluxHandle<String, String> handle = new FluxHandle<>(Flux.just("a"), new StringBuilderAdapter(), listener);

    handle.get();
    handle.cancel();

    assertTrue(listener.completed.get());
    assertFalse(listener.cancelled.get());
  }

  @Test
  void error_shouldReturnPartialResultAndSetErrorState() {
    Flux<String> flux = Flux.concat(
        Flux.just("Hello", " "),
        Flux.error(new RuntimeException("Network error"))
    );
    RecordingListener listener = new RecordingListener();

    FluxHandle<String, String> handle = new FluxHandle<>(flux, new StringBuilderAdapter(), listener);
    String result = handle.get();

    assertEquals("Hello ", result);
    assertTrue(handle.isError());
    assertFalse(handle.isCancelled());
    assertNotNull(handle.getError());
    assertNotNull(listener.error.get());
  }

  @Test
  void assemblerException_shouldWrapInFluxAssemblerException() {
    FluxAssembler<String, String> failingAssembler = new FluxAssembler<>() {
      @Override
      public void applyDelta(String delta) {
        throw new RuntimeException("applyDelta failed");
      }

      @Override
      public String build() {
        return "";
      }
    };

    FluxHandle<String, String> handle = new FluxHandle<>(Flux.just("a"), failingAssembler, item -> {});
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxAssemblerException.class, handle.getError());
  }

  @Test
  void listenerException_shouldWrapInFluxListenerException() {
    FluxListener<String> failingListener = item -> {
      throw new RuntimeException("listener failed");
    };

    FluxHandle<String, String> handle = new FluxHandle<>(Flux.just("a"), new StringBuilderAdapter(), failingListener);
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxListenerException.class, handle.getError());
  }

}
