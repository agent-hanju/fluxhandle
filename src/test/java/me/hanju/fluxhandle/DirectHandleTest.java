package me.hanju.fluxhandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import me.hanju.fluxhandle.exception.FluxAssemblerException;
import me.hanju.fluxhandle.exception.FluxListenerException;

class DirectHandleTest {

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
    assertThrows(IllegalArgumentException.class, () -> new DirectHandle<>(null, item -> {
    }));
    assertThrows(IllegalArgumentException.class, () -> new DirectHandle<>(new StringBuilderAdapter(), null));
  }

  @Test
  void emitAndComplete_shouldReturnBuiltResult() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), listener);

    handle.onNext("a");
    handle.onNext("b");
    handle.onNext("c");
    handle.onComplete();

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
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), item -> {
    });

    handle.onNext("x");
    handle.onNext("y");
    handle.onComplete();

    assertEquals("xy", handle.get(5, TimeUnit.SECONDS));
  }

  @Test
  void getWithTimeout_shouldThrowOnNullUnit() {
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), item -> {
    });
    handle.onComplete();
    assertThrows(IllegalArgumentException.class, () -> handle.get(1, null));
  }

  @Test
  void getWithTimeout_shouldThrowTimeoutException() {
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), item -> {
    });
    assertThrows(TimeoutException.class, () -> handle.get(100, TimeUnit.MILLISECONDS));
  }

  @Test
  void cancel_shouldReturnPartialResult() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), listener);

    handle.onNext("Hello");
    handle.onNext(" ");
    handle.cancel();

    String result = handle.get();

    assertEquals("Hello ", result);
    assertTrue(handle.isCancelled());
    assertTrue(listener.cancelled.get());
    assertFalse(listener.completed.get());
  }

  @Test
  void cancel_afterCompleteShouldHaveNoEffect() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), listener);

    handle.onNext("a");
    handle.onComplete();
    handle.cancel();

    assertTrue(listener.completed.get());
    assertFalse(listener.cancelled.get());
    assertFalse(handle.isCancelled());
  }

  @Test
  void onError_shouldReturnPartialResultAndSetErrorState() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), listener);

    handle.onNext("Hello");
    handle.onNext(" ");
    handle.onError(new RuntimeException("Network error"));

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

    DirectHandle<String, String> handle = new DirectHandle<>(failingAssembler, item -> {
    });
    handle.onNext("a");
    handle.onComplete();

    assertTrue(handle.isError());
    assertInstanceOf(FluxAssemblerException.class, handle.getError());
  }

  @Test
  void listenerException_shouldWrapInFluxListenerException() {
    FluxListener<String> failingListener = item -> {
      throw new RuntimeException("listener failed");
    };

    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), failingListener);
    handle.onNext("a");
    handle.onComplete();

    assertTrue(handle.isError());
    assertInstanceOf(FluxListenerException.class, handle.getError());
  }

  @Test
  void onNextAfterComplete_shouldBeIgnored() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), listener);

    handle.onNext("a");
    handle.onComplete();
    handle.onNext("b");

    assertEquals("a", handle.get());
    assertEquals(List.of("a"), listener.items);
  }

  @Test
  void handleInterface_shouldBeCompatible() {
    Handle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), item -> {
    });

    ((DirectHandle<String, String>) handle).onNext("test");
    ((DirectHandle<String, String>) handle).onComplete();

    assertEquals("test", handle.get());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
  }

  @Test
  void asyncEmit_shouldWorkCorrectly() throws Exception {
    RecordingListener listener = new RecordingListener();
    DirectHandle<String, String> handle = new DirectHandle<>(new StringBuilderAdapter(), listener);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch latch = new CountDownLatch(1);

    executor.submit(() -> {
      try {
        Thread.sleep(50);
        handle.onNext("async");
        handle.onComplete();
        latch.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    String result = handle.get(5, TimeUnit.SECONDS);
    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertEquals("async", result);
    executor.shutdown();
  }
}
