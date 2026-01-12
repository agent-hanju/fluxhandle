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

import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import reactor.core.publisher.Flux;

class SimpleFluxHandleTest {

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
        new SimpleFluxHandle<>(null, StringChunk.class, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        new SimpleFluxHandle<>(Flux.just(new StringChunk("a")), null, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        new SimpleFluxHandle<>(Flux.just(new StringChunk("a")), StringChunk.class, null));
  }

  @Test
  void get_shouldReturnBuiltResult() {
    Flux<StringChunk> flux = Flux.just(
        new StringChunk("a"),
        new StringChunk("b"),
        new StringChunk("c")
    );
    RecordingListener listener = new RecordingListener();

    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(flux, StringChunk.class, listener);
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
    Flux<StringChunk> flux = Flux.just(new StringChunk("x"), new StringChunk("y"));
    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(flux, StringChunk.class, item -> {});

    assertEquals("xy", handle.get(5, TimeUnit.SECONDS).getContent());
  }

  @Test
  void getWithTimeout_shouldThrowOnNullUnit() {
    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(
        Flux.just(new StringChunk("a")), StringChunk.class, item -> {});
    assertThrows(IllegalArgumentException.class, () -> handle.get(1, null));
  }

  @Test
  void getWithTimeout_shouldThrowTimeoutException() {
    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(Flux.never(), StringChunk.class, item -> {});
    assertThrows(TimeoutException.class, () -> handle.get(100, TimeUnit.MILLISECONDS));
  }

  @Test
  void cancel_shouldStopStreamAndReturnPartialResult() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Flux<StringChunk> flux = Flux.interval(Duration.ofMillis(50))
        .map(i -> new StringChunk("item" + i))
        .doOnCancel(latch::countDown);

    RecordingListener listener = new RecordingListener();
    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(flux, StringChunk.class, listener);

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
    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(
        Flux.just(new StringChunk("a")), StringChunk.class, listener);

    handle.get();
    handle.cancel();

    assertTrue(listener.completed.get());
    assertFalse(listener.cancelled.get());
  }

  @Test
  void error_shouldReturnPartialResultAndSetErrorState() {
    Flux<StringChunk> flux = Flux.concat(
        Flux.just(new StringChunk("Hello"), new StringChunk(" ")),
        Flux.error(new RuntimeException("Network error"))
    );
    RecordingListener listener = new RecordingListener();

    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(flux, StringChunk.class, listener);
    StringChunk result = handle.get();

    assertEquals("Hello ", result.getContent());
    assertTrue(handle.isError());
    assertFalse(handle.isCancelled());
    assertNotNull(handle.getError());
    assertNotNull(listener.error.get());
  }

  @Test
  void listenerException_shouldWrapInFluxListenerException() {
    FluxListener<StringChunk> failingListener = item -> {
      throw new RuntimeException("listener failed");
    };

    SimpleFluxHandle<StringChunk> handle = new SimpleFluxHandle<>(
        Flux.just(new StringChunk("a")), StringChunk.class, failingListener);
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxListenerException.class, handle.getError());
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
    SimpleFluxHandle<FailingChunk> handle = new SimpleFluxHandle<>(
        Flux.just(new FailingChunk("a"), new FailingChunk("b")),
        FailingChunk.class,
        item -> {});
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxHandleException.class, handle.getError());
  }

  @Test
  void iFluxHandleInterface_shouldBeCompatible() {
    IFluxHandle<StringChunk, StringChunk> handle = new SimpleFluxHandle<>(
        Flux.just(new StringChunk("test")),
        StringChunk.class,
        item -> {});

    assertEquals("test", handle.get().getContent());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
  }
}
