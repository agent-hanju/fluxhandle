# TODO

## FluxHandle 리스너 타입 변경

### 현재 상태
- `FluxHandle<T, R>`에서 `FluxListener<T>` 사용 (원본 델타 전달)

### 변경 필요
- `FluxListener<T>` → `FluxListener<R>` (변환된 델타 전달)

### 이유
- FluxHandle<T, R>의 목적은 T를 R로 변환해서 처리하는 것
- 리스너가 T를 받으면 변환 결과(R)를 스트리밍으로 받을 방법이 없음
- 0:N 매핑 시 각 R마다 리스너 호출이 더 직관적

### 변경 내용

**FluxHandle.java**
```java
// Before
private final FluxListener<T> listener;

public FluxHandle(
    Flux<T> flux,
    DeltaMapper<T, R> mapper,
    Class<R> resultType,
    FluxListener<T> listener  // T
)

// After
private final FluxListener<R> listener;

public FluxHandle(
    Flux<T> flux,
    DeltaMapper<T, R> mapper,
    Class<R> resultType,
    FluxListener<R> listener  // R
)
```

**onNext 로직 변경**
```java
// Before
listener.onNext(item);           // T 전달
List<R> mapped = mapper.map(item);
for (R delta : mapped) {
    merger.applyDelta(delta);
}

// After
List<R> mapped = mapper.map(item);
for (R delta : mapped) {
    merger.applyDelta(delta);
    listener.onNext(delta);      // R 전달 (각각)
}
```

### 영향받는 파일
- `FluxHandle.java`
- `FluxHandleTest.java`
- `IFluxHandle.java` (Javadoc만)

### 사용 예시 변경
```java
// Before
FluxHandle<SdkChunk, MyDelta> handle = new FluxHandle<>(
    flux, mapper, MyDelta.class,
    chunk -> log.info("원본: {}", chunk)  // SdkChunk
);

// After
FluxHandle<SdkChunk, MyDelta> handle = new FluxHandle<>(
    flux, mapper, MyDelta.class,
    delta -> log.info("변환됨: {}", delta)  // MyDelta
);
```
