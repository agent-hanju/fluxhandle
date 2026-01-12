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

import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;

class DirectHandleTest {

  /**
   * Test class with custom merge method for string concatenation.
   */
  public static class StringChunk {
    private String content;

    public StringChunk() {
    }

    public StringChunk(String content) {
      this.content = content;
    }

    public String getContent() {
      return content;
    }

    public StringChunk merge(StringChunk delta) {
      String newContent = (this.content == null ? "" : this.content)
          + (delta.content == null ? "" : delta.content);
      return new StringChunk(newContent);
    }
  }

  public static class RecordingListener implements FluxListener<StringChunk> {
    final List<StringChunk> items = new ArrayList<>();
    final AtomicBoolean completed = new AtomicBoolean(false);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onNext(StringChunk item) {
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
        new DirectHandle<>(null, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        new DirectHandle<>(StringChunk.class, null));
  }

  @Test
  void emitAndComplete_shouldReturnBuiltResult() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, listener);

    handle.onNext(new StringChunk("a"));
    handle.onNext(new StringChunk("b"));
    handle.onNext(new StringChunk("c"));
    handle.onComplete();

    StringChunk result = handle.get();

    assertEquals("abc", result.getContent());
    assertEquals(3, listener.items.size());
    assertTrue(listener.completed.get());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
    assertNull(handle.getError());
  }

  @Test
  void getWithTimeout_shouldReturnBuiltResult() throws TimeoutException {
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, item -> {});

    handle.onNext(new StringChunk("x"));
    handle.onNext(new StringChunk("y"));
    handle.onComplete();

    assertEquals("xy", handle.get(5, TimeUnit.SECONDS).getContent());
  }

  @Test
  void getWithTimeout_shouldThrowOnNullUnit() {
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, item -> {});
    handle.onComplete();
    assertThrows(IllegalArgumentException.class, () -> handle.get(1, null));
  }

  @Test
  void getWithTimeout_shouldThrowTimeoutException() {
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, item -> {});
    assertThrows(TimeoutException.class, () -> handle.get(100, TimeUnit.MILLISECONDS));
  }

  @Test
  void cancel_shouldReturnPartialResult() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, listener);

    handle.onNext(new StringChunk("Hello"));
    handle.onNext(new StringChunk(" "));
    handle.cancel();

    StringChunk result = handle.get();

    assertEquals("Hello ", result.getContent());
    assertTrue(handle.isCancelled());
    assertTrue(listener.cancelled.get());
    assertFalse(listener.completed.get());
  }

  @Test
  void cancel_afterCompleteShouldHaveNoEffect() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, listener);

    handle.onNext(new StringChunk("a"));
    handle.onComplete();
    handle.cancel();

    assertTrue(listener.completed.get());
    assertFalse(listener.cancelled.get());
    assertFalse(handle.isCancelled());
  }

  @Test
  void onError_shouldReturnPartialResultAndSetErrorState() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, listener);

    handle.onNext(new StringChunk("Hello"));
    handle.onNext(new StringChunk(" "));
    handle.onError(new RuntimeException("Network error"));

    StringChunk result = handle.get();

    assertEquals("Hello ", result.getContent());
    assertTrue(handle.isError());
    assertFalse(handle.isCancelled());
    assertNotNull(handle.getError());
    assertNotNull(listener.error.get());
  }

  /**
   * Test class that throws exception in merge method.
   */
  public static class FailingChunk {
    private String content;

    public FailingChunk() {
    }

    public FailingChunk(String content) {
      this.content = content;
    }

    public String getContent() {
      return content;
    }

    public FailingChunk merge(FailingChunk delta) {
      throw new RuntimeException("merge failed");
    }
  }

  @Test
  void mergeException_shouldWrapInFluxHandleException() {
    DirectHandle<FailingChunk> handle = new DirectHandle<>(FailingChunk.class, item -> {});
    handle.onNext(new FailingChunk("a"));
    handle.onNext(new FailingChunk("b"));
    handle.onComplete();

    assertTrue(handle.isError());
    assertInstanceOf(FluxHandleException.class, handle.getError());
  }

  @Test
  void listenerException_shouldWrapInFluxListenerException() {
    FluxListener<StringChunk> failingListener = item -> {
      throw new RuntimeException("listener failed");
    };

    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, failingListener);
    handle.onNext(new StringChunk("a"));
    handle.onComplete();

    assertTrue(handle.isError());
    assertInstanceOf(FluxListenerException.class, handle.getError());
  }

  @Test
  void onNextAfterComplete_shouldBeIgnored() {
    RecordingListener listener = new RecordingListener();
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, listener);

    handle.onNext(new StringChunk("a"));
    handle.onComplete();
    handle.onNext(new StringChunk("b"));

    assertEquals("a", handle.get().getContent());
    assertEquals(1, listener.items.size());
  }

  @Test
  void handleInterface_shouldBeCompatible() {
    Handle<StringChunk> handle = new DirectHandle<>(StringChunk.class, item -> {});

    ((DirectHandle<StringChunk>) handle).onNext(new StringChunk("test"));
    ((DirectHandle<StringChunk>) handle).onComplete();

    assertEquals("test", handle.get().getContent());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
  }

  @Test
  void asyncEmit_shouldWorkCorrectly() throws Exception {
    RecordingListener listener = new RecordingListener();
    DirectHandle<StringChunk> handle = new DirectHandle<>(StringChunk.class, listener);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch latch = new CountDownLatch(1);

    executor.submit(() -> {
      try {
        Thread.sleep(50);
        handle.onNext(new StringChunk("async"));
        handle.onComplete();
        latch.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    StringChunk result = handle.get(5, TimeUnit.SECONDS);
    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertEquals("async", result.getContent());
    executor.shutdown();
  }
}
