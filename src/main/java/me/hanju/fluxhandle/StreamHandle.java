package me.hanju.fluxhandle;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import me.hanju.fluxhandle.exception.FluxHandleException;
import me.hanju.fluxhandle.exception.FluxListenerException;
import me.hanju.streambind.map.StreamMapper;
import me.hanju.streambind.merge.StreamMerger;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 직접 방출과 Flux 구독을 모두 지원하는 유연한 스트리밍 핸들로,
 * 선택적 델타 변환과 자동 병합을 제공한다.
 *
 * <p>
 * StreamHandle은 다음을 제공하는 핵심 구현체이다:
 * <ul>
 * <li>{@link #emitNext(Object)}, {@link #emitError(Throwable)}, {@link #emitComplete()}를 통한 직접 방출</li>
 * <li>{@link #subscribe(Flux)} 또는 {@link #subscribe(Flux, StreamMapper)}를 통한 Flux 구독</li>
 * <li>구독 교체 지원 - 다른 Flux 소스로 전환 가능</li>
 * <li>{@link StreamMerger}를 통한 자동 델타 병합</li>
 * </ul>
 *
 * <p>
 * 델타 병합은 필드 타입에 따라 자동으로 처리된다:
 * <ul>
 * <li>String: 추가 (연결)</li>
 * <li>Number: 합계 (덧셈)</li>
 * <li>Object: 재귀적 병합</li>
 * <li>원시 타입 List: 확장</li>
 * <li>Object List: 인덱스 기반 병합 ({@code @StreamIndex} 필요)</li>
 * </ul>
 *
 * <p>
 * 직접 방출 사용 예시:
 *
 * <pre>{@code
 * StreamHandle<String> handle = new StreamHandle<>(String.class, s -> System.out.println(s));
 *
 * handle.emitNext("Hello");
 * handle.emitNext(" World");
 * handle.emitComplete();
 *
 * String result = handle.get();  // "Hello World"
 * }</pre>
 *
 * <p>
 * Flux 구독 사용 예시:
 *
 * <pre>{@code
 * StreamHandle<String> handle = new StreamHandle<>(String.class, s -> System.out.println(s));
 * handle.subscribe(Flux.just("Hello", " ", "World"));
 *
 * String result = handle.get();  // "Hello World"
 * }</pre>
 *
 * <p>
 * 변환 사용 예시:
 *
 * <pre>{@code
 * StreamHandle<MyDelta> handle = new StreamHandle<>(MyDelta.class, delta -> {});
 *
 * StreamMapper<SdkChunk, MyDelta> mapper = chunk -> List.of(new MyDelta(chunk.getContent()));
 * handle.subscribe(sdkStream, mapper);
 *
 * MyDelta result = handle.get();
 * }</pre>
 *
 * @param <R> 결과 및 방출되는 델타의 타입
 * @see Handle
 * @see StreamMapper
 * @see FluxListener
 */
public class StreamHandle<R> {
  private static final Logger log = LoggerFactory.getLogger(StreamHandle.class);

  private final FluxListener<R> listener;
  private final StreamMerger<R> merger;
  private final CompletableFuture<R> future = new CompletableFuture<>();

  private Disposable disposable = null;
  private StreamMapper<?, R> currentMapper = null;
  private Throwable error = null;
  private boolean completed = false;
  private boolean cancelled = false;

  /**
   * 주어진 결과 타입과 리스너로 새 StreamHandle을 생성한다.
   *
   * @param resultType 결과 타입의 클래스
   * @param listener   스트리밍 이벤트를 수신할 리스너
   * @throws IllegalArgumentException 파라미터 중 하나라도 null인 경우
   */
  public StreamHandle(
      final Class<R> resultType,
      final FluxListener<R> listener) {
    if (resultType == null) {
      throw new IllegalArgumentException("resultType cannot be null");
    } else if (listener == null) {
      throw new IllegalArgumentException("listener cannot be null");
    }
    this.merger = new StreamMerger<>(resultType);
    this.listener = listener;
  }

  /**
   * 변환 없이 주어진 Flux를 구독한다.
   *
   * <p>
   * 이전 구독이 있으면 새 Flux를 구독하기 전에 해제된다.
   * 구독은 bounded elastic 스케줄러에서 수행된다.
   *
   * @param flux 구독할 리액티브 스트림
   * @throws IllegalArgumentException flux가 null인 경우
   * @throws IllegalStateException    핸들이 이미 완료된 경우
   */
  public synchronized void subscribe(final Flux<R> flux) {
    subscribe(flux, List::of);
  }

  /**
   * 매퍼를 통한 변환과 함께 주어진 Flux를 구독한다.
   *
   * <p>
   * 이전 구독이 있으면 새 Flux를 구독하기 전에 해제된다.
   * 구독은 bounded elastic 스케줄러에서 수행된다.
   *
   * <p>
   * 참고: 상태를 가진 매퍼를 사용하고 구독을 교체하는 경우, 적절한 상태 관리를 보장하라.
   * 새로운 상태를 원하면 새 매퍼 인스턴스를 전달하고, 누적을 계속하려면 동일한 매퍼를 재사용하라.
   *
   * @param <T>    Flux의 입력 요소 타입
   * @param flux   구독할 리액티브 스트림
   * @param mapper 입력 델타를 결과 타입으로 변환하는 델타 매퍼
   * @throws IllegalArgumentException flux나 mapper가 null인 경우
   * @throws IllegalStateException    핸들이 이미 완료된 경우
   */
  public synchronized <T> void subscribe(final Flux<T> flux, final StreamMapper<T, R> mapper) {
    if (flux == null) {
      throw new IllegalArgumentException("flux cannot be null");
    }
    if (mapper == null) {
      throw new IllegalArgumentException("mapper cannot be null");
    }
    if (this.completed) {
      throw new IllegalStateException("cannot subscribe after completion");
    }
    if (this.disposable != null) {
      this.disposable.dispose();
      log.debug("previous subscription disposed");
    }
    this.currentMapper = mapper;
    this.disposable = flux.subscribeOn(Schedulers.boundedElastic())
        .subscribe(
            item -> this.onNext(item, mapper),
            this::onError,
            () -> this.onComplete(mapper));
    log.debug("subscribed to new flux");
  }

  /**
   * 결과 아이템을 핸들에 직접 방출한다.
   *
   * <p>
   * 아이템은 누적된 결과에 병합되고 리스너의
   * {@link FluxListener#onNext(Object)}가 호출된다.
   *
   * @param item 방출할 결과 아이템
   */
  public synchronized void emitNext(final R item) {
    if (this.completed) {
      log.warn("emitting next failed. already completed.");
      return;
    }

    try {
      this.merger.applyDelta(item);
    } catch (final Exception e) {
      this.onError(new FluxHandleException("delta merge failed", e));
      return;
    }

    try {
      this.listener.onNext(item);
    } catch (final Exception ex) {
      this.onError(new FluxListenerException("listener failed while emit next", ex));
      return;
    }

    log.debug("emitted directly: {}", item);
  }

  /**
   * 에러를 핸들에 방출한다.
   *
   * <p>
   * 리스너의 {@link FluxListener#onError(Throwable)}가 호출되고
   * 핸들은 완료로 표시된다.
   *
   * @param e 방출할 에러
   */
  public synchronized void emitError(final Throwable e) {
    this.onError(e);
  }

  /**
   * 핸들을 정상적으로 완료한다.
   *
   * <p>
   * 리스너의 {@link FluxListener#onComplete()}가 호출되고
   * 결과는 {@link #get()}을 통해 사용 가능하다.
   */
  public synchronized void emitComplete() {
    if (this.completed) {
      log.warn("emitting complete failed. already completed.");
    } else {
      final R result;
      try {
        result = this.merger.build();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("merge build failed while complete", e));
        return;
      }
      try {
        this.listener.onComplete();
      } catch (final Exception e) {
        this.onError(new FluxListenerException("listener failed while complete", e));
        return;
      }
      this.future.complete(result);
      this.completed = true;
      log.info("completed");
    }
  }

  private <T> void onNext(final T item, final StreamMapper<T, R> mapper) {
    if (this.completed) {
      log.warn("emitting next failed. already completed.");
      return;
    }

    // 1. Transform delta (0:N mapping)
    final List<R> mappedDeltas;
    try {
      mappedDeltas = mapper.map(item);
    } catch (final Exception e) {
      this.onError(new FluxHandleException("delta mapping failed", e));
      return;
    }

    // 2. Merge each transformed delta and notify listener
    for (final R delta : mappedDeltas) {
      try {
        this.merger.applyDelta(delta);
      } catch (final Exception e) {
        this.onError(new FluxHandleException("delta merge failed", e));
        return;
      }

      try {
        this.listener.onNext(delta);
      } catch (final Exception ex) {
        this.onError(new FluxListenerException("listener failed while emit next", ex));
        return;
      }
    }

    log.debug("emitted: {} -> {} mapped", item, mappedDeltas.size());
  }

  private void onError(final Throwable e) {
    log.info("received an error", e);
    if (this.completed) {
      log.warn("emitting error failed. already completed.");
    } else {
      try {
        this.listener.onError(e);
      } catch (final Exception ex) {
        log.warn("listener.onError failed", ex);
        e.addSuppressed(new FluxListenerException("listener failed while error", ex));
      }
      this.error = e;
      this.completed = true;
      try {
        this.future.complete(this.merger.build());
      } catch (final Exception ex) {
        log.warn("merger.build failed", ex);
        e.addSuppressed(new FluxHandleException("merge build failed while error", ex));
        this.future.completeExceptionally(e);
      }
    }
  }

  private <T> void onComplete(final StreamMapper<T, R> mapper) {
    if (this.completed) {
      log.warn("emitting complete failed. already completed.");
    } else {
      // Flush remaining buffered deltas from mapper
      final List<R> flushedDeltas;
      try {
        flushedDeltas = mapper.flush();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("delta flush failed", e));
        return;
      }

      for (final R delta : flushedDeltas) {
        try {
          this.merger.applyDelta(delta);
        } catch (final Exception e) {
          this.onError(new FluxHandleException("delta merge failed during flush", e));
          return;
        }

        try {
          this.listener.onNext(delta);
        } catch (final Exception ex) {
          this.onError(new FluxListenerException("listener failed during flush", ex));
          return;
        }
      }

      final R result;
      try {
        result = this.merger.build();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("merge build failed while complete", e));
        return;
      }
      try {
        this.listener.onComplete();
      } catch (final Exception e) {
        this.onError(new FluxListenerException("listener failed while complete", e));
        return;
      }
      this.future.complete(result);
      this.completed = true;
      log.info("completed");
    }
  }

  /**
   * 스트리밍을 취소하고 리스너에 알린다.
   *
   * <p>
   * 이미 완료된 경우 이 메서드는 아무 효과가 없다.
   * 현재까지 누적된 결과는 {@link #get()}을 통해 여전히 사용 가능하다.
   */

  public synchronized void cancel() {
    if (this.completed) {
      log.warn("cancel failed. already completed.");
    } else {
      if (this.disposable != null) {
        this.disposable.dispose();
      }

      // Flush remaining buffered deltas from mapper if present
      if (this.currentMapper != null) {
        final List<R> flushedDeltas;
        try {
          flushedDeltas = this.currentMapper.flush();
        } catch (final Exception e) {
          this.onError(new FluxHandleException("delta flush failed during cancel", e));
          return;
        }

        for (final R delta : flushedDeltas) {
          try {
            this.merger.applyDelta(delta);
          } catch (final Exception e) {
            this.onError(new FluxHandleException("delta merge failed during cancel flush", e));
            return;
          }

          try {
            this.listener.onNext(delta);
          } catch (final Exception ex) {
            this.onError(new FluxListenerException("listener failed during cancel flush", ex));
            return;
          }
        }
      }

      final R result;
      try {
        result = this.merger.build();
      } catch (final Exception e) {
        this.onError(new FluxHandleException("build failed while cancel", e));
        return;
      }
      try {
        this.listener.onCancel();
      } catch (final Exception ex) {
        this.onError(new FluxListenerException("listener failed while cancel", ex));
        return;
      }
      this.cancelled = true;
      this.completed = true;
      this.future.complete(result);
      log.info("cancelled");
    }
  }

  /**
   * 이 핸들이 취소되었는지 반환한다.
   *
   * @return 취소된 경우 {@code true}, 그렇지 않으면 {@code false}
   */

  public boolean isCancelled() {
    return this.cancelled;
  }

  /**
   * 스트리밍 중 에러가 발생했는지 반환한다.
   *
   * @return 에러가 발생한 경우 {@code true}, 그렇지 않으면 {@code false}
   */

  public boolean isError() {
    return this.error != null;
  }

  /**
   * 스트리밍 중 발생한 에러를 반환한다 (있는 경우).
   *
   * @return 에러, 또는 에러가 발생하지 않은 경우 {@code null}
   */

  public Throwable getError() {
    return this.error;
  }

  /**
   * 스트림이 완료될 때까지 블로킹하고 빌드된 결과를 반환한다.
   *
   * @return {@code R} 타입의 병합된 결과
   * @throws FluxHandleException 스트리밍 중 에러가 발생한 경우
   */

  public R get() {
    try {
      return future.get();
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof final FluxHandleException fhe) {
        throw fhe;
      } else {
        throw new FluxHandleException("unexpected", e.getCause());
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FluxHandleException("interrupted", e);
    }
  }

  /**
   * 스트림이 완료되거나 타임아웃이 만료될 때까지 블로킹한 후 빌드된 결과를 반환한다.
   *
   * @param timeout 최대 대기 시간
   * @param unit    타임아웃 인자의 시간 단위
   * @return {@code R} 타입의 병합된 결과
   * @throws TimeoutException         대기 시간이 초과된 경우
   * @throws IllegalArgumentException unit이 null인 경우
   * @throws FluxHandleException      스트리밍 중 에러가 발생한 경우
   */

  public R get(final long timeout, final TimeUnit unit) throws TimeoutException {
    if (unit == null) {
      throw new IllegalArgumentException("unit cannot be null");
    }
    try {
      return future.get(timeout, unit);
    } catch (final ExecutionException e) {
      if (e.getCause() instanceof final FluxHandleException fhe) {
        throw fhe;
      } else {
        throw new FluxHandleException("unexpected", e.getCause());
      }
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FluxHandleException("interrupted", e);
    }
  }
}
