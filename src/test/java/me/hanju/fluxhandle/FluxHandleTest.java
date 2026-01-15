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

import me.hanju.streambind.map.StreamMapper;
import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import reactor.core.publisher.Flux;

class FluxHandleTest {

  /**
   * Input type for transformation tests.
   */
  public static class InputChunk {
    private String content;
    private int index;

    public InputChunk() {
    }

    public InputChunk(String content, int index) {
      this.content = content;
      this.index = index;
    }

    public String getContent() {
      return content;
    }

    public int getIndex() {
      return index;
    }
  }

  /**
   * Output type with custom merge method.
   */
  public static class OutputDelta {
    private String text;

    public OutputDelta() {
    }

    public OutputDelta(String text) {
      this.text = text;
    }

    public String getText() {
      return text;
    }

    public OutputDelta merge(OutputDelta delta) {
      String newText = (this.text == null ? "" : this.text)
          + (delta.text == null ? "" : delta.text);
      return new OutputDelta(newText);
    }
  }

  public static class RecordingListener<T> implements FluxListener<T> {
    final List<T> items = new ArrayList<>();
    final AtomicBoolean completed = new AtomicBoolean(false);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onNext(T item) {
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

  // Simple 1:1 mapper
  private static final StreamMapper<InputChunk, OutputDelta> SIMPLE_MAPPER =
      chunk -> List.of(new OutputDelta(chunk.getContent()));

  @Test
  void constructor_shouldThrowOnNullArguments() {
    StreamMapper<InputChunk, OutputDelta> mapper = chunk -> List.of(new OutputDelta(chunk.getContent()));

    assertThrows(IllegalArgumentException.class, () ->
        FluxHandle.of(null, mapper, OutputDelta.class, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        FluxHandle.of(Flux.just(new InputChunk("a", 0)), null, OutputDelta.class, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        FluxHandle.of(Flux.just(new InputChunk("a", 0)), mapper, null, item -> {}));
    assertThrows(IllegalArgumentException.class, () ->
        FluxHandle.of(Flux.just(new InputChunk("a", 0)), mapper, OutputDelta.class, null));
  }

  @Test
  void get_shouldReturnTransformedAndMergedResult() {
    Flux<InputChunk> flux = Flux.just(
        new InputChunk("a", 0),
        new InputChunk("b", 1),
        new InputChunk("c", 2)
    );
    RecordingListener<OutputDelta> listener = new RecordingListener<>();

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, SIMPLE_MAPPER, OutputDelta.class, listener);
    OutputDelta result = handle.get();

    assertEquals("abc", result.getText());
    assertEquals(3, listener.items.size());
    assertTrue(listener.completed.get());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
    assertNull(handle.getError());
  }

  @Test
  void get_withFilteringMapper_shouldSkipEmptyResults() {
    // Mapper that filters out chunks with even index
    StreamMapper<InputChunk, OutputDelta> filteringMapper = chunk -> {
      if (chunk.getIndex() % 2 == 0) {
        return List.of();  // Filter out
      }
      return List.of(new OutputDelta(chunk.getContent()));
    };

    Flux<InputChunk> flux = Flux.just(
        new InputChunk("a", 0),  // filtered
        new InputChunk("b", 1),  // kept
        new InputChunk("c", 2),  // filtered
        new InputChunk("d", 3)   // kept
    );
    RecordingListener<OutputDelta> listener = new RecordingListener<>();

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, filteringMapper, OutputDelta.class, listener);
    OutputDelta result = handle.get();

    assertEquals("bd", result.getText());
    assertEquals(2, listener.items.size());  // Listener receives only transformed deltas (filtered)
  }

  @Test
  void get_withExpandingMapper_shouldMergeMultipleOutputs() {
    // Mapper that splits each chunk into multiple outputs
    StreamMapper<InputChunk, OutputDelta> expandingMapper = chunk -> {
      List<OutputDelta> outputs = new ArrayList<>();
      for (char c : chunk.getContent().toCharArray()) {
        outputs.add(new OutputDelta(String.valueOf(c)));
      }
      return outputs;
    };

    Flux<InputChunk> flux = Flux.just(
        new InputChunk("ab", 0),
        new InputChunk("cd", 1)
    );

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, expandingMapper, OutputDelta.class, item -> {});
    OutputDelta result = handle.get();

    assertEquals("abcd", result.getText());
  }

  @Test
  void getWithTimeout_shouldReturnBuiltResult() throws TimeoutException {
    Flux<InputChunk> flux = Flux.just(new InputChunk("x", 0), new InputChunk("y", 1));
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, SIMPLE_MAPPER, OutputDelta.class, item -> {});

    assertEquals("xy", handle.get(5, TimeUnit.SECONDS).getText());
  }

  @Test
  void getWithTimeout_shouldThrowOnNullUnit() {
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        Flux.just(new InputChunk("a", 0)), SIMPLE_MAPPER, OutputDelta.class, item -> {});
    assertThrows(IllegalArgumentException.class, () -> handle.get(1, null));
  }

  @Test
  void getWithTimeout_shouldThrowTimeoutException() {
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        Flux.never(), SIMPLE_MAPPER, OutputDelta.class, item -> {});
    assertThrows(TimeoutException.class, () -> handle.get(100, TimeUnit.MILLISECONDS));
  }

  @Test
  void cancel_shouldStopStreamAndReturnPartialResult() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Flux<InputChunk> flux = Flux.interval(Duration.ofMillis(50))
        .map(i -> new InputChunk("item" + i, i.intValue()))
        .doOnCancel(latch::countDown);

    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, SIMPLE_MAPPER, OutputDelta.class, listener);

    Thread.sleep(120);
    handle.cancel();

    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertTrue(handle.isCancelled());
    assertTrue(listener.cancelled.get());
    assertFalse(listener.items.isEmpty());
  }

  @Test
  void cancel_afterCompleteShouldHaveNoEffect() {
    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        Flux.just(new InputChunk("a", 0)), SIMPLE_MAPPER, OutputDelta.class, listener);

    handle.get();
    handle.cancel();

    assertTrue(listener.completed.get());
    assertFalse(listener.cancelled.get());
  }

  @Test
  void error_shouldReturnPartialResultAndSetErrorState() {
    Flux<InputChunk> flux = Flux.concat(
        Flux.just(new InputChunk("Hello", 0), new InputChunk(" ", 1)),
        Flux.error(new RuntimeException("Network error"))
    );
    RecordingListener<OutputDelta> listener = new RecordingListener<>();

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, SIMPLE_MAPPER, OutputDelta.class, listener);
    OutputDelta result = handle.get();

    assertEquals("Hello ", result.getText());
    assertTrue(handle.isError());
    assertFalse(handle.isCancelled());
    assertNotNull(handle.getError());
    assertNotNull(listener.error.get());
  }

  @Test
  void listenerException_shouldWrapInFluxListenerException() {
    FluxListener<OutputDelta> failingListener = item -> {
      throw new RuntimeException("listener failed");
    };

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        Flux.just(new InputChunk("a", 0)), SIMPLE_MAPPER, OutputDelta.class, failingListener);
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxListenerException.class, handle.getError());
  }

  @Test
  void mapperException_shouldWrapInFluxHandleException() {
    StreamMapper<InputChunk, OutputDelta> failingMapper = chunk -> {
      throw new RuntimeException("mapping failed");
    };

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        Flux.just(new InputChunk("a", 0)), failingMapper, OutputDelta.class, item -> {});
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxHandleException.class, handle.getError());
  }

  /**
   * Output type that throws exception in merge method.
   */
  public static class FailingOutput {
    private String content;

    public FailingOutput() {
    }

    public FailingOutput(String content) {
      this.content = content;
    }

    public String getContent() {
      return content;
    }

    public FailingOutput merge(FailingOutput delta) {
      throw new RuntimeException("merge failed");
    }
  }

  @Test
  void mergeException_shouldWrapInFluxHandleException() {
    StreamMapper<InputChunk, FailingOutput> mapper = chunk -> List.of(new FailingOutput(chunk.getContent()));

    FluxHandle<FailingOutput> handle = FluxHandle.of(
        Flux.just(new InputChunk("a", 0), new InputChunk("b", 1)),
        mapper,
        FailingOutput.class,
        item -> {});
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxHandleException.class, handle.getError());
  }

  @Test
  void handleInterface_shouldBeCompatible() {
    Handle<OutputDelta> handle = FluxHandle.of(
        Flux.just(new InputChunk("test", 0)),
        SIMPLE_MAPPER,
        OutputDelta.class,
        item -> {});

    assertEquals("test", handle.get().getText());
    assertFalse(handle.isCancelled());
    assertFalse(handle.isError());
  }

  @Test
  void statefulMapper_shouldMaintainStateAcrossDeltas() {
    // Stateful mapper that accumulates content
    StreamMapper<InputChunk, OutputDelta> statefulMapper = new StreamMapper<>() {
      private final StringBuilder buffer = new StringBuilder();

      @Override
      public List<OutputDelta> map(InputChunk chunk) {
        buffer.append(chunk.getContent());
        return List.of(new OutputDelta("[" + buffer + "]"));
      }
    };

    Flux<InputChunk> flux = Flux.just(
        new InputChunk("a", 0),
        new InputChunk("b", 1),
        new InputChunk("c", 2)
    );

    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, statefulMapper, OutputDelta.class, item -> {});
    OutputDelta result = handle.get();

    // Each delta has accumulated state: [a] + [ab] + [abc]
    assertEquals("[a][ab][abc]", result.getText());
  }

  @Test
  void flush_shouldBeCalledOnComplete() {
    // Buffering mapper that only emits on newline, with flush for remaining
    StreamMapper<InputChunk, OutputDelta> bufferingMapper = new StreamMapper<>() {
      private final StringBuilder buffer = new StringBuilder();

      @Override
      public List<OutputDelta> map(InputChunk chunk) {
        buffer.append(chunk.getContent());
        List<OutputDelta> results = new ArrayList<>();
        int idx;
        while ((idx = buffer.indexOf("\n")) >= 0) {
          results.add(new OutputDelta(buffer.substring(0, idx + 1)));
          buffer.delete(0, idx + 1);
        }
        return results;
      }

      @Override
      public List<OutputDelta> flush() {
        if (buffer.isEmpty()) {
          return List.of();
        }
        String remaining = buffer.toString();
        buffer.setLength(0);
        return List.of(new OutputDelta(remaining));
      }
    };

    Flux<InputChunk> flux = Flux.just(
        new InputChunk("line1\nline", 0),
        new InputChunk("2", 1)  // no trailing newline
    );

    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, bufferingMapper, OutputDelta.class, listener);
    OutputDelta result = handle.get();

    // "line1\n" emitted during map, "line2" emitted during flush
    assertEquals("line1\nline2", result.getText());
    assertEquals(2, listener.items.size());
    assertEquals("line1\n", listener.items.get(0).getText());
    assertEquals("line2", listener.items.get(1).getText());
  }

  @Test
  void flush_shouldBeCalledOnCancel() throws Exception {
    // Buffering mapper with flush
    StreamMapper<InputChunk, OutputDelta> bufferingMapper = new StreamMapper<>() {
      private final StringBuilder buffer = new StringBuilder();

      @Override
      public List<OutputDelta> map(InputChunk chunk) {
        buffer.append(chunk.getContent());
        return List.of();  // Buffer everything, emit nothing during map
      }

      @Override
      public List<OutputDelta> flush() {
        if (buffer.isEmpty()) {
          return List.of();
        }
        String remaining = buffer.toString();
        buffer.setLength(0);
        return List.of(new OutputDelta(remaining));
      }
    };

    CountDownLatch latch = new CountDownLatch(1);
    Flux<InputChunk> flux = Flux.interval(Duration.ofMillis(50))
        .map(i -> new InputChunk("x", i.intValue()))
        .doOnCancel(latch::countDown);

    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    FluxHandle<OutputDelta> handle = FluxHandle.of(
        flux, bufferingMapper, OutputDelta.class, listener);

    Thread.sleep(150);  // Let some items buffer
    handle.cancel();

    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertTrue(handle.isCancelled());

    // Flush should have been called, so listener should have received 1 delta
    assertEquals(1, listener.items.size());
    assertTrue(listener.items.get(0).getText().startsWith("x"));  // At least one 'x' buffered
  }
}
