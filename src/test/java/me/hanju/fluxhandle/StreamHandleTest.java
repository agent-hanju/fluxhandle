package me.hanju.fluxhandle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import me.hanju.streambind.map.StreamMapper;
import reactor.core.publisher.Flux;

class StreamHandleTest {

  /**
   * 변환 테스트용 입력 타입.
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
   * 커스텀 병합 메서드를 가진 출력 타입.
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

  // 단순 1:1 매퍼
  private static final StreamMapper<InputChunk, OutputDelta> SIMPLE_MAPPER = chunk -> List
      .of(new OutputDelta(chunk.getContent()));

  @Test
  void constructor_shouldThrowOnNullArguments() {
    assertThrows(IllegalArgumentException.class, () -> new StreamHandle<>(null, item -> {
    }));
    assertThrows(IllegalArgumentException.class, () -> new StreamHandle<>(OutputDelta.class, null));
  }

  @Test
  void subscribe_shouldThrowOnNullArguments() {
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });

    assertThrows(IllegalArgumentException.class, () -> handle.subscribe(null));
    assertThrows(IllegalArgumentException.class, () -> handle.subscribe(null, SIMPLE_MAPPER));
    assertThrows(IllegalArgumentException.class, () -> handle.subscribe(Flux.just(new InputChunk("a", 0)), null));
  }

  @Test
  void get_shouldReturnTransformedAndMergedResult() {
    Flux<InputChunk> flux = Flux.just(
        new InputChunk("a", 0),
        new InputChunk("b", 1),
        new InputChunk("c", 2));
    RecordingListener<OutputDelta> listener = new RecordingListener<>();

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux, SIMPLE_MAPPER);
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
    // 짝수 인덱스를 필터링하는 매퍼
    StreamMapper<InputChunk, OutputDelta> filteringMapper = chunk -> {
      if (chunk.getIndex() % 2 == 0) {
        return List.of(); // 필터 아웃
      }
      return List.of(new OutputDelta(chunk.getContent()));
    };

    Flux<InputChunk> flux = Flux.just(
        new InputChunk("a", 0), // 필터됨
        new InputChunk("b", 1), // 유지
        new InputChunk("c", 2), // 필터됨
        new InputChunk("d", 3) // 유지
    );
    RecordingListener<OutputDelta> listener = new RecordingListener<>();

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux, filteringMapper);
    OutputDelta result = handle.get();

    assertEquals("bd", result.getText());
    assertEquals(2, listener.items.size()); // 리스너는 변환된 델타만 수신 (필터링됨)
  }

  @Test
  void get_withExpandingMapper_shouldMergeMultipleOutputs() {
    // 각 청크를 여러 출력으로 분할하는 매퍼
    StreamMapper<InputChunk, OutputDelta> expandingMapper = chunk -> {
      List<OutputDelta> outputs = new ArrayList<>();
      for (char c : chunk.getContent().toCharArray()) {
        outputs.add(new OutputDelta(String.valueOf(c)));
      }
      return outputs;
    };

    Flux<InputChunk> flux = Flux.just(
        new InputChunk("ab", 0),
        new InputChunk("cd", 1));

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(flux, expandingMapper);
    OutputDelta result = handle.get();

    assertEquals("abcd", result.getText());
  }

  @Test
  void getWithTimeout_shouldReturnBuiltResult() throws TimeoutException {
    Flux<InputChunk> flux = Flux.just(new InputChunk("x", 0), new InputChunk("y", 1));
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(flux, SIMPLE_MAPPER);

    assertEquals("xy", handle.get(5, TimeUnit.SECONDS).getText());
  }

  @Test
  void getWithTimeout_shouldThrowOnNullUnit() {
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(Flux.just(new InputChunk("a", 0)), SIMPLE_MAPPER);
    assertThrows(IllegalArgumentException.class, () -> handle.get(1, null));
  }

  @Test
  void getWithTimeout_shouldThrowTimeoutException() {
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(Flux.never(), SIMPLE_MAPPER);
    assertThrows(TimeoutException.class, () -> handle.get(100, TimeUnit.MILLISECONDS));
  }

  @Test
  void cancel_shouldStopStreamAndReturnPartialResult() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    Flux<InputChunk> flux = Flux.interval(Duration.ofMillis(50))
        .map(i -> new InputChunk("item" + i, i.intValue()))
        .doOnCancel(latch::countDown);

    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux, SIMPLE_MAPPER);

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
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(Flux.just(new InputChunk("a", 0)), SIMPLE_MAPPER);

    handle.get();
    handle.cancel();

    assertTrue(listener.completed.get());
    assertFalse(listener.cancelled.get());
  }

  @Test
  void error_shouldReturnPartialResultAndSetErrorState() {
    Flux<InputChunk> flux = Flux.concat(
        Flux.just(new InputChunk("Hello", 0), new InputChunk(" ", 1)),
        Flux.error(new RuntimeException("Network error")));
    RecordingListener<OutputDelta> listener = new RecordingListener<>();

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux, SIMPLE_MAPPER);
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

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, failingListener);
    handle.subscribe(Flux.just(new InputChunk("a", 0)), SIMPLE_MAPPER);
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxListenerException.class, handle.getError());
  }

  @Test
  void mapperException_shouldWrapInFluxHandleException() {
    StreamMapper<InputChunk, OutputDelta> failingMapper = chunk -> {
      throw new RuntimeException("mapping failed");
    };

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(Flux.just(new InputChunk("a", 0)), failingMapper);
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxHandleException.class, handle.getError());
  }

  /**
   * 병합 메서드에서 예외를 던지는 출력 타입.
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

    StreamHandle<FailingOutput> handle = new StreamHandle<>(FailingOutput.class, item -> {
    });
    handle.subscribe(
        Flux.just(new InputChunk("a", 0), new InputChunk("b", 1)),
        mapper);
    handle.get();

    assertTrue(handle.isError());
    assertInstanceOf(FluxHandleException.class, handle.getError());
  }

  @Test
  void statefulMapper_shouldMaintainStateAcrossDeltas() {
    // 상태를 누적하는 매퍼
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
        new InputChunk("c", 2));

    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(flux, statefulMapper);
    OutputDelta result = handle.get();

    // 각 델타는 누적된 상태를 가짐: [a] + [ab] + [abc]
    assertEquals("[a][ab][abc]", result.getText());
  }

  @Test
  void flush_shouldBeCalledOnComplete() {
    // 개행에서만 방출하는 버퍼링 매퍼, 나머지는 flush에서 방출
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
        new InputChunk("2", 1) // 후행 개행 없음
    );

    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux, bufferingMapper);
    OutputDelta result = handle.get();

    // "line1\n"은 map 중에 방출, "line2"는 flush 중에 방출
    assertEquals("line1\nline2", result.getText());
    assertEquals(2, listener.items.size());
    assertEquals("line1\n", listener.items.get(0).getText());
    assertEquals("line2", listener.items.get(1).getText());
  }

  @Test
  void flush_shouldBeCalledOnCancel() throws Exception {
    // flush가 있는 버퍼링 매퍼
    StreamMapper<InputChunk, OutputDelta> bufferingMapper = new StreamMapper<>() {
      private final StringBuilder buffer = new StringBuilder();

      @Override
      public List<OutputDelta> map(InputChunk chunk) {
        buffer.append(chunk.getContent());
        return List.of(); // 모든 것을 버퍼링하고 map 중에는 아무것도 방출하지 않음
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
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux, bufferingMapper);

    Thread.sleep(150); // 일부 아이템이 버퍼링되도록 대기
    handle.cancel();

    assertTrue(latch.await(1, TimeUnit.SECONDS));
    assertTrue(handle.isCancelled());

    // flush가 호출되었으므로 리스너는 1개의 델타를 수신해야 함
    assertEquals(1, listener.items.size());
    assertTrue(listener.items.get(0).getText().startsWith("x")); // 최소 하나의 'x'가 버퍼링됨
  }

  @Test
  void emitNext_shouldWorkWithDirectEmission() {
    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);

    handle.emitNext(new OutputDelta("Hello"));
    handle.emitNext(new OutputDelta(" "));
    handle.emitNext(new OutputDelta("World"));
    handle.emitComplete();

    OutputDelta result = handle.get();
    assertEquals("Hello World", result.getText());
    assertEquals(3, listener.items.size());
    assertTrue(listener.completed.get());
  }

  @Test
  void emitError_shouldSetErrorState() {
    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);

    handle.emitNext(new OutputDelta("Hello"));
    handle.emitError(new RuntimeException("test error"));

    OutputDelta result = handle.get();
    assertEquals("Hello", result.getText());
    assertTrue(handle.isError());
    assertNotNull(listener.error.get());
  }

  @Test
  void subscribeReplacement_shouldDisposeOldSubscription() throws Exception {
    CountDownLatch disposeLatch = new CountDownLatch(1);
    Flux<OutputDelta> flux1 = Flux.interval(Duration.ofMillis(50))
        .map(i -> new OutputDelta("old" + i))
        .doOnCancel(disposeLatch::countDown);
    Flux<OutputDelta> flux2 = Flux.just(new OutputDelta("new"));

    RecordingListener<OutputDelta> listener = new RecordingListener<>();
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, listener);
    handle.subscribe(flux1);

    Thread.sleep(100); // flux1에서 일부 아이템 수신
    handle.subscribe(flux2); // flux1 구독 교체

    assertTrue(disposeLatch.await(1, TimeUnit.SECONDS));
    OutputDelta result = handle.get();
    assertTrue(result.getText().contains("new"));
  }

  @Test
  void subscribeAfterComplete_shouldThrow() {
    StreamHandle<OutputDelta> handle = new StreamHandle<>(OutputDelta.class, item -> {
    });
    handle.subscribe(Flux.just(new OutputDelta("test")));
    handle.get(); // 완료 대기

    assertThrows(IllegalStateException.class, () -> handle.subscribe(Flux.just(new OutputDelta("another"))));
  }
}
