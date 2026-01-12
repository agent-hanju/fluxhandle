# FluxHandle

리액티브 스트림을 위한 경량 스트리밍 툴킷. 델타 병합과 리스너 기반 콜백을 지원합니다.

## 주요 기능

- **FluxHandle / DirectHandle** - `Flux` 스트림 구독 또는 수동 emit으로 리스너 기반 콜백 처리
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

### 기본 FluxHandle 사용법

```java
Flux<String> flux = Flux.just("Hello", " ", "World");

FluxHandle<String, String> handle = new FluxHandle<>(
    flux,
    DeltaMerger.stringMerger(),
    item -> System.out.println("수신: " + item)
);

String result = handle.get();  // "Hello World"
```

### 복잡한 객체의 델타 병합

AI 채팅 응답처럼 객체가 증분 델타로 도착하는 스트리밍 시나리오:

```java
// 스트리밍 객체 정의
public class ChatChoice {
    private Integer index;  // index 필드 자동 감지
    private ChatMessage message;
}

// 델타를 완전한 객체로 병합
DeltaMerger<ChatChoice> merger = DeltaMerger.create(ChatChoice.class);

FluxHandle<ChatChoice, List<ChatChoice>> handle = new FluxHandle<>(
    chatStream,
    merger,
    choice -> System.out.println("델타: " + choice)
);

List<ChatChoice> choices = handle.get();  // 완전히 병합된 choices
```

## 구성 요소

### Handle 인터페이스

모든 handle 구현체의 공통 인터페이스:

- `get()` / `get(timeout, unit)` - 블로킹 후 결과 조회
- `cancel()` - 스트리밍 취소
- `isCancelled()` / `isError()` / `getError()` - 상태 확인

### FluxHandle<T, R>

`Flux<T>`를 래핑하고 스트리밍 생명주기를 관리. 생성 시 즉시 구독됨.

### DirectHandle<T, R>

`Flux` 소스 없이 직접 항목을 emit:

```java
DirectHandle<String, String> handle = new DirectHandle<>(
    DeltaMerger.stringMerger(),
    listener
);

handle.onNext("first");
handle.onNext("second");
handle.onComplete();

String result = handle.get();  // "firstsecond"
```

### DeltaMerger<T>

스트리밍 델타를 완전한 객체로 병합. 지원 기능:

- **기본 타입** - 문자열, 숫자 (연결/덧셈)
- **인덱스 객체** - `index` 필드가 있는 객체는 그룹화 후 병합
- **중첩 객체** - 재귀적 병합
- **커스텀 merge 메서드** - `T merge(T delta)` 정의로 커스텀 로직 사용

```java
// 단순 문자열 연결
DeltaMerger<String> stringMerger = DeltaMerger.stringMerger();

// 인덱스 기반 그룹화가 필요한 복잡한 객체
DeltaMerger<MyClass> merger = DeltaMerger.create(MyClass.class);
```

### FluxListener<T>

스트리밍 이벤트 수신 인터페이스:

- `onNext(T item)` - 각 항목 emit 시 호출
- `onError(Throwable e)` - 에러 발생 시 호출 (기본: 경고 로그)
- `onComplete()` - 정상 완료 시 호출
- `onCancel()` - 취소 시 호출

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

- `FluxAssemblerException` - assembler 작업 오류
- `FluxListenerException` - 리스너 콜백 오류
- `MergeException` - 델타 병합 오류
- `MetadataException` - 메타데이터 처리 오류

## 요구 사항

- Java 21+
- Project Reactor Core

## 마이그레이션 가이드

### 0.2.x → 0.3.0

`FluxAssembler` 인터페이스가 `DeltaMerger`로 교체되었습니다.

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
FluxHandle<String, String> handle = new FluxHandle<>(
    flux,
    DeltaMerger.stringMerger(),
    listener
);
```

## 라이선스

MIT License - [LICENSE](LICENSE) 참조
