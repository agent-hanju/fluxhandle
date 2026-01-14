# FluxHandle

리액티브 스트림을 위한 경량 스트리밍 툴킷. 델타 변환, 병합과 리스너 기반 콜백을 지원합니다.

## 주요 기능

- **StreamHandle** - 지연 구독, 구독 교체, 직접 방출을 모두 지원하는 핵심 핸들
- **FluxHandle** - 생성 시 즉시 구독하는 편의 래퍼
- **DeltaMapper** - 상태를 가질 수 있는 0:N 델타 변환
- **DeltaMerger** - 리플렉션 기반 자동 델타 병합 (AI 채팅 응답, 증분 업데이트 등)
- `get()` 또는 `get(timeout, unit)`으로 동기 결과 조회
- `cancel()`로 취소 지원
- `FluxHandleException` 계층 구조의 예외 처리

## 설치

### JitPack

JitPack 저장소 추가:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

의존성 추가:

```groovy
dependencies {
    implementation 'com.github.agent-hanju:fluxhandle:0.4.0'
}
```

## 빠른 시작

### FluxHandle - 즉시 구독

생성 시 바로 Flux를 구독하는 간편한 방식:

```java
// 변환 없이 사용 (입력 == 결과 타입)
FluxHandle<String> handle = FluxHandle.of(
    Flux.just("Hello", " ", "World"),
    String.class,
    item -> System.out.println("수신: " + item)
);

String result = handle.get();  // "Hello World"
```

```java
// DeltaMapper로 변환하며 사용
DeltaMapper<SdkChunk, MyDelta> mapper = chunk ->
    List.of(new MyDelta(chunk.getContent(), chunk.getIndex()));

FluxHandle<MyDelta> handle = FluxHandle.of(
    sdkStream,
    mapper,
    MyDelta.class,
    delta -> System.out.println("변환된 델타: " + delta)
);

MyDelta result = handle.get();
```

### StreamHandle - 유연한 핸들

지연 구독, 구독 교체, 직접 방출이 필요한 경우:

```java
// 핸들 생성 (아직 구독 안 함)
StreamHandle<MyDelta> handle = new StreamHandle<>(
    MyDelta.class,
    delta -> System.out.println("수신: " + delta)
);

// 나중에 구독 시작
handle.subscribe(flux);

// 또는 변환과 함께 구독
handle.subscribe(sdkStream, mapper);

MyDelta result = handle.get();
```

```java
// 직접 방출 (Flux 없이)
StreamHandle<ChatChunk> handle = new StreamHandle<>(
    ChatChunk.class,
    chunk -> System.out.println("수신: " + chunk)
);

handle.emitNext(chunk1);
handle.emitNext(chunk2);
handle.emitComplete();

ChatChunk result = handle.get();  // 병합된 결과
```

### 복잡한 객체의 델타 병합

AI 채팅 응답처럼 객체가 증분 델타로 도착하는 스트리밍 시나리오:

```java
// 스트리밍 객체 정의
public class ChatCompletionChunk {
    private List<ChatChoice> choices;  // choices 안의 index 필드로 자동 병합
}

public class ChatChoice {
    private Integer index;  // index 필드 자동 감지
    private ChatMessage message;
}

// FluxHandle로 자동 델타 병합
FluxHandle<ChatCompletionChunk> handle = FluxHandle.of(
    chatStream,
    ChatCompletionChunk.class,
    chunk -> System.out.println("델타: " + chunk)
);

ChatCompletionChunk result = handle.get();  // 완전히 병합된 결과
```

## 구성 요소

### Handle 인터페이스

모든 handle 구현체의 공통 인터페이스 (`Handle<R>`):

- `get()` / `get(timeout, unit)` - 블로킹 후 결과 조회
- `cancel()` - 스트리밍 취소
- `isCancelled()` / `isError()` / `getError()` - 상태 확인

### StreamHandle\<R\>

핵심 핸들 구현. 지연 구독, 구독 교체, 직접 방출을 모두 지원:

```java
StreamHandle<MyDelta> handle = new StreamHandle<>(MyDelta.class, listener);

// Flux 구독
handle.subscribe(flux);                    // 변환 없이
handle.subscribe(flux, mapper);            // DeltaMapper로 변환

// 직접 방출
handle.emitNext(delta);
handle.emitError(exception);
handle.emitComplete();
```

#### subscribe 시 mapper를 전달하는 이유

`DeltaMapper`는 **상태를 가진 변환**(버퍼링, 줄 단위 분할 등)을 위해 설계되었습니다. Flux 구독 시 mapper를 전달하면, handle이 mapper의 라이프사이클을 관리합니다. 스트림 완료나 취소 시 `flush()`가 자동 호출되어 버퍼에 남은 데이터가 처리됩니다.

```java
// stateful mapper - handle이 flush 자동 호출
handle.subscribe(flux, bufferingMapper);
```

**stateless 변환은 Flux에서 직접 처리**: 상태가 필요 없는 단순 변환은 Flux의 `map()`이나 `flatMap()`을 사용하는 것이 더 간단합니다.

```java
// stateless 변환 - Flux.map() 사용 권장
Flux<MyDelta> transformed = sdkStream.map(chunk -> new MyDelta(chunk.getContent()));
handle.subscribe(transformed);
```

#### emitNext 사용 시

직접 방출할 때는 이미 변환된 `R` 타입을 전달합니다. mapper를 사용하려면 호출자가 직접 변환하고 flush도 직접 호출해야 합니다.

```java
// 호출자가 mapper 관리
DeltaMapper<SdkChunk, MyDelta> mapper = ...;

for (SdkChunk chunk : chunks) {
    for (MyDelta delta : mapper.map(chunk)) {
        handle.emitNext(delta);
    }
}
// flush도 호출자 책임
for (MyDelta delta : mapper.flush()) {
    handle.emitNext(delta);
}
handle.emitComplete();
```

### FluxHandle\<R\>

`StreamHandle`의 편의 래퍼. 생성 시 즉시 구독:

```java
// 변환 없이
FluxHandle<String> handle = FluxHandle.of(flux, String.class, listener);

// 변환과 함께
FluxHandle<MyDelta> handle = FluxHandle.of(flux, mapper, MyDelta.class, listener);
```

### DeltaMapper\<T, R\>

상태를 가질 수 있는 델타 변환 인터페이스. 0:N 매핑을 지원하여 필터링, 버퍼링, 분할이 가능:

```java
// 1:1 변환
DeltaMapper<SdkChunk, MyDelta> mapper = chunk ->
    List.of(new MyDelta(chunk.getContent()));

// 버퍼링 (줄 단위 분할)
DeltaMapper<String, String> lineMapper = new DeltaMapper<>() {
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public List<String> map(String chunk) {
        buffer.append(chunk);
        List<String> lines = new ArrayList<>();
        int idx;
        while ((idx = buffer.indexOf("\n")) >= 0) {
            lines.add(buffer.substring(0, idx));
            buffer.delete(0, idx + 1);
        }
        return lines;  // 0개 또는 N개 반환
    }

    @Override
    public List<String> flush() {
        // 스트림 완료/취소 시 남은 버퍼 처리
        if (buffer.isEmpty()) return List.of();
        String remaining = buffer.toString();
        buffer.setLength(0);
        return List.of(remaining);
    }
};
```

### DeltaMerger\<T\>

스트리밍 델타를 완전한 객체로 병합하는 내부 컴포넌트. 핸들이 자동으로 생성하므로 직접 다룰 필요가 없습니다.

지원하는 병합 규칙:

- **String** - 연결 (concatenation)
- **Number (래퍼 타입)** - 합산 (Integer, Long, Double 등)
- **Primitive 타입** - 덮어쓰기 (int, long, double, boolean 등)
- **Object** - 재귀적 병합
- **Primitive List** - 확장 (extend)
- **Object List** - `index` 필드 기반 그룹화 후 병합
- **커스텀 merge 메서드** - 클래스에 `T merge(T delta)` 메서드 정의 시 자동 사용

> **Note**: primitive 타입(`int`, `long`, `double`, `boolean` 등)은 null을 표현할 수 없어 "변경 없음"을 구분할 수 없습니다. 따라서 항상 덮어쓰기됩니다. 합산이 필요하면 래퍼 타입(`Integer`, `Long`, `Double`)을 사용하세요.

```java
// 커스텀 병합 로직 예제
public class ChatMessage {
    private String content;

    // 이 메서드가 있으면 자동으로 사용됨
    public ChatMessage merge(ChatMessage delta) {
        if (delta.content != null) {
            this.content = (this.content == null ? "" : this.content) + delta.content;
        }
        return this;
    }
}
```

### FluxListener\<R\>

스트리밍 이벤트 수신 인터페이스. 함수형 인터페이스로 간단한 람다 사용 가능:

```java
FluxListener<String> listener = item -> System.out.println("수신: " + item);
```

전체 이벤트를 처리하려면:

- `onNext(R item)` - 각 항목 emit 시 호출 (필수)
- `onError(Throwable e)` - 에러 발생 시 호출 (기본: 경고 로그)
- `onComplete()` - 정상 완료 시 호출 (기본: 빈 구현)
- `onCancel()` - 취소 시 호출 (기본: 빈 구현)

## DeltaStream 패키지

`deltastream` 패키지는 효율적인 델타 병합을 위한 리플렉션 기반 메타데이터 캐싱을 제공:

### @StreamIndex 어노테이션

커스텀 인덱스 필드 지정 (필드명이 `index`면 불필요):

```java
public class ToolCall {
    @StreamIndex
    private Integer idx;  // 커스텀 인덱스 필드
    private String id;
}
```

### TypeMetadataCache

스레드 세이프 메타데이터 캐싱:

```java
TypeInfo info = TypeMetadataCache.getTypeInfo(MyClass.class);
info.indexFieldName();  // 인덱스 필드명 조회
info.fields();          // 전체 필드 메타데이터 조회
```

자세한 내용은 [deltastream-package.md](docs/deltastream-package.md) 참조.

## 설계 철학: 에러/취소 시 부분 결과 반환

일반적인 `Future` 구현과 달리, handle은 에러가 발생하거나 스트림이 취소되어도 **항상 정상 완료**되며 그 시점까지 구축된 부분 결과를 반환합니다.

| 이벤트 | `get()` 반환값 | `isError()` | `isCancelled()` |
|-------|---------------|-------------|-----------------|
| 정상 완료 | 전체 결과 | `false` | `false` |
| 스트림 중 에러 | **부분 결과** | `true` | `false` |
| `cancel()` 호출 | **부분 결과** | `false` | `true` |

### 왜 이런 설계인가?

스트리밍 시나리오(AI 채팅 응답, 대용량 파일 다운로드, 실시간 데이터 피드)에서는 중단되더라도 **부분 결과를 보존**하고 싶은 경우가 많습니다.

```java
String result = handle.get();  // 에러 시에도 부분 결과 반환
if (handle.isError()) {
    log.warn("부분 결과 (원인: {})", handle.getError().getMessage());
}
```

## 예외 처리

모든 예외는 `FluxHandleException`을 상속:

- `FluxHandleException` - 일반적인 handle 작업 오류
- `FluxListenerException` - 리스너 콜백 오류
- `MergeException` - 델타 병합 오류
- `MetadataException` - 메타데이터 처리 오류 (리플렉션 실패 등)

## 요구 사항

- Java 21+
- Project Reactor Core

## 마이그레이션 가이드

### 0.3.x → 0.4.0

클래스 구조가 단순화되었습니다.

**Before (0.3.x):**
```java
// SimpleFluxHandle 사용
SimpleFluxHandle<String> handle = new SimpleFluxHandle<>(
    flux,
    String.class,
    listener
);

// FluxHandle 사용
FluxHandle<Input, Output> handle = new FluxHandle<>(
    flux,
    mapper,
    Output.class,
    listener
);

// EmitHandle 사용
EmitHandle<MyDelta> handle = new EmitHandle<>(MyDelta.class, listener);
handle.emitNext(delta);
handle.emitComplete();
```

**After (0.4.0):**
```java
// FluxHandle.of() 사용 (변환 없이)
FluxHandle<String> handle = FluxHandle.of(
    flux,
    String.class,
    listener
);

// FluxHandle.of() 사용 (변환과 함께)
FluxHandle<Output> handle = FluxHandle.of(
    flux,
    mapper,
    Output.class,
    listener
);

// StreamHandle 직접 방출
StreamHandle<MyDelta> handle = new StreamHandle<>(MyDelta.class, listener);
handle.emitNext(delta);
handle.emitComplete();
```

주요 변화:
- `IFluxHandle` → `Handle` (인터페이스 리네임)
- `Handle<T, R>` → `Handle<R>` (입력 타입 제네릭 제거)
- `SimpleFluxHandle` 삭제 → `FluxHandle.of(flux, resultType, listener)` 사용
- `EmitHandle` 삭제 → `StreamHandle`의 `emitNext()` / `emitComplete()` 사용
- `FluxHandle<T, R>` → `FluxHandle<R>` (입력 타입 제네릭 제거)
- `new FluxHandle<>()` → `FluxHandle.of()` (정적 팩토리 메서드)

## 라이선스

MIT License - [LICENSE](LICENSE) 참조
