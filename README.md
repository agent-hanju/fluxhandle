# FluxHandle

리액티브 스트림을 위한 경량 스트리밍 툴킷. 델타 변환, 병합과 리스너 기반 콜백을 지원합니다.

## 주요 기능

- **SimpleFluxHandle** - 입력과 결과 타입이 동일한 단순 스트리밍 (`T → T`)
- **FluxHandle** - 델타 변환이 필요한 스트리밍 (`T → R`, `DeltaMapper` 사용)
- **DeltaStream** - 리플렉션 기반 델타 병합 (AI 채팅 응답, 증분 업데이트 등)
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
    implementation 'com.github.agent-hanju:fluxhandle:0.3.0'
}
```

## 빠른 시작

### SimpleFluxHandle - 단순 스트리밍

입력 타입과 결과 타입이 동일한 경우 사용:

```java
Flux<String> flux = Flux.just("Hello", " ", "World");

SimpleFluxHandle<String> handle = new SimpleFluxHandle<>(
    flux,
    String.class,
    item -> System.out.println("수신: " + item)
);

String result = handle.get();  // "Hello World"
```

### FluxHandle - 델타 변환 스트리밍

SDK 응답을 도메인 객체로 변환하는 경우:

```java
// DeltaMapper로 변환 로직 정의 (0:N 매핑 지원)
DeltaMapper<SdkChunk, MyDelta> mapper = chunk ->
    List.of(new MyDelta(chunk.getContent(), chunk.getIndex()));

FluxHandle<SdkChunk, MyDelta> handle = new FluxHandle<>(
    sdkStream,
    mapper,
    MyDelta.class,
    delta -> System.out.println("변환된 델타: " + delta)
);

MyDelta result = handle.get();
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

// SimpleFluxHandle 생성 - 자동으로 델타 병합 수행
SimpleFluxHandle<ChatCompletionChunk> handle = new SimpleFluxHandle<>(
    chatStream,
    ChatCompletionChunk.class,
    chunk -> System.out.println("델타: " + chunk)
);

ChatCompletionChunk result = handle.get();  // 완전히 병합된 결과
```

## 구성 요소

### IFluxHandle 인터페이스

모든 handle 구현체의 공통 인터페이스 (`IFluxHandle<T, R>`):

- `get()` / `get(timeout, unit)` - 블로킹 후 결과 조회
- `cancel()` - 스트리밍 취소
- `isCancelled()` / `isError()` / `getError()` - 상태 확인

### SimpleFluxHandle\<T\>

입력과 결과 타입이 동일한 경우(`T == T`) 사용. `Flux<T>`를 래핑하고 스트리밍 생명주기를 관리. 생성 시 즉시 구독되며, 내부적으로 `DeltaMerger`를 생성하여 자동 델타 병합을 수행.

```java
SimpleFluxHandle<String> handle = new SimpleFluxHandle<>(
    flux,
    String.class,
    listener
);
```

### FluxHandle\<T, R\>

입력 타입 `T`를 결과 타입 `R`로 변환해야 하는 경우 사용. `DeltaMapper`를 통해 각 델타를 변환한 후 병합.

```java
FluxHandle<SdkChunk, MyDelta> handle = new FluxHandle<>(
    flux,
    mapper,      // DeltaMapper<T, R>
    MyDelta.class,
    listener
);
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
};
```

### DeltaMerger\<T\>

스트리밍 델타를 완전한 객체로 병합하는 내부 컴포넌트. `FluxHandle`과 `SimpleFluxHandle`이 생성 시 자동으로 생성하므로 직접 다룰 필요가 없습니다.

지원하는 병합 규칙:

- **String** - 연결 (concatenation)
- **Number** - 합산 (addition)
- **Object** - 재귀적 병합
- **Primitive List** - 확장 (extend)
- **Object List** - `index` 필드 기반 그룹화 후 병합
- **커스텀 merge 메서드** - 클래스에 `T merge(T delta)` 메서드 정의 시 자동 사용

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

### 0.2.x → 0.3.0

`FluxAssembler` 인터페이스가 `DeltaMerger`로 교체되고, 클래스 구조가 변경되었습니다.

**Before (0.2.x):**
```java
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
    listener
);
```

**After (0.3.0):**
```java
// 입력 == 결과 타입인 경우: SimpleFluxHandle 사용
SimpleFluxHandle<String> handle = new SimpleFluxHandle<>(
    flux,
    String.class,
    listener
);

// 변환이 필요한 경우: FluxHandle + DeltaMapper 사용
FluxHandle<Input, Output> handle = new FluxHandle<>(
    flux,
    input -> List.of(transform(input)),  // DeltaMapper
    Output.class,
    listener
);
```

주요 변화:
- `Handle` → `IFluxHandle` (인터페이스 리네임)
- `DirectHandle` → `SimpleFluxHandle` (클래스 리네임 + 역할 변경)
- `FluxHandle<T>` → `FluxHandle<T, R>` (변환 기능 추가)
- 단순 케이스(`T == T`)는 `SimpleFluxHandle` 사용
- 변환 케이스(`T → R`)는 `FluxHandle` + `DeltaMapper` 사용

## 라이선스

MIT License - [LICENSE](LICENSE) 참조
