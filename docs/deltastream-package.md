# Delta Stream 패키지 사용 가이드

> `me.hanju.fluxhandle.deltastream` 패키지 사용법

---

## 개요

델타 스트리밍 객체 병합을 위한 리플렉션 기반 메타데이터 캐싱 유틸리티.
FluxHandle 외에 다른 곳에서도 독립적으로 사용 가능.

---

## 기초 지식: Java Annotation이란?

### Annotation 기본 개념

**Annotation(어노테이션)** 은 Java 코드에 **메타데이터**를 추가하는 방법입니다.
`@` 기호로 시작하며, 컴파일러나 런타임에 특별한 처리를 지시합니다.

```java
// 익숙한 예시들
@Override           // 메서드 오버라이드 검증
@Deprecated         // 사용 중단 경고
@SuppressWarnings   // 특정 경고 무시
```

### Annotation의 동작 시점

| Retention | 설명 | 예시 |
|-----------|------|------|
| `SOURCE` | 컴파일 시 제거됨 | `@Override` |
| `CLASS` | .class 파일에 남지만 런타임에 접근 불가 | 기본값 |
| `RUNTIME` | 런타임에 리플렉션으로 읽을 수 있음 | `@StreamIndex` |

### 커스텀 Annotation 정의

```java
@Target(ElementType.FIELD)      // 필드에만 적용 가능
@Retention(RetentionPolicy.RUNTIME)  // 런타임에 읽을 수 있음
public @interface StreamIndex {
    // @interface 키워드로 어노테이션 정의
}
```

### Reflection으로 Annotation 읽기

```java
Field field = MyClass.class.getDeclaredField("index");

// 어노테이션 존재 여부 확인
if (field.isAnnotationPresent(StreamIndex.class)) {
    // 이 필드에 @StreamIndex가 붙어있음
}
```

---

## 왜 메타데이터 캐싱이 필요한가?

### Reflection의 문제점

```java
// 매번 리플렉션 호출 - 느림!
for (Object delta : deltas) {
    Field[] fields = delta.getClass().getDeclaredFields();  // 비용 큼
    for (Field f : fields) {
        f.setAccessible(true);  // 비용 큼
        Object value = f.get(delta);  // 비용 큼
    }
}
```

### 캐싱으로 해결

```java
// 한 번만 분석하고 캐시
TypeInfo info = TypeMetadataCache.getTypeInfo(MyClass.class);  // 캐시됨

// 이후 빠르게 사용
for (Object delta : deltas) {
    for (FieldMetadata field : info.fields()) {
        Object value = field.getValue(delta);  // 캐시된 정보 사용
    }
}
```

이것이 `TypeMetadataCache`가 존재하는 이유입니다.

---

## 구성 요소

| 클래스 | 패키지 | 역할 |
|--------|--------|------|
| `@StreamIndex` | `annotation` | 커스텀 인덱스 필드 지정용 어노테이션 |
| `TypeMetadataCache` | `metadata` | 타입별 메타데이터 캐시 (스레드 세이프) |
| `TypeMetadataCache.TypeInfo` | `metadata` | 캐시된 타입 정보 (필드 목록, 인덱스 필드, merge 메서드) |
| `FieldMetadata` | `metadata` | 개별 필드 메타데이터 |
| `MetadataException` | `metadata` | 메타데이터 처리 오류 예외 |
| `DeltaMerger` | `merge` | 델타 병합 로직 |
| `ObjectBuilder` | `merge` | Map → 객체 변환 유틸리티 |
| `MergeException` | `merge` | 병합 오류 예외 |

---

## 사용법

### 1. 기본 사용 - index 필드 자동 인식

`index`라는 이름의 필드는 자동으로 인식됨:

```java
public class Choice {
    private Integer index;  // 자동 인식
    private String content;
}

TypeInfo info = TypeMetadataCache.getTypeInfo(Choice.class);
info.indexFieldName();  // "index"
```

### 2. 커스텀 인덱스 필드

`index`가 아닌 다른 이름을 쓸 때만 `@StreamIndex` 사용:

```java
public class ToolCall {
    @StreamIndex
    private Integer idx;  // 어노테이션으로 지정
    private String id;
}

TypeInfo info = TypeMetadataCache.getTypeInfo(ToolCall.class);
info.indexFieldName();  // "idx"
```

### 3. 필드 정보 읽기

```java
TypeInfo info = TypeMetadataCache.getTypeInfo(MyClass.class);

for (FieldMetadata field : info.fields()) {
    String name = field.fieldName();
    Object value = field.getValue(instance);

    if (field.isString()) { ... }
    if (field.isNumber()) { ... }
    if (field.isList()) {
        if (field.isPrimitiveList()) { ... }  // List<String>, List<Integer>
        if (field.isObjectList()) { ... }     // List<SomeObject>
    }
    if (field.isObject()) { ... }  // 중첩 객체
}
```

### 4. merge 메서드 감지

클래스에 `T merge(T delta)` 메서드가 있으면 자동 감지:

```java
public class CustomChunk {
    private String content;

    public CustomChunk merge(CustomChunk delta) {
        return new CustomChunk(this.content + delta.content);
    }
}

TypeInfo info = TypeMetadataCache.getTypeInfo(CustomChunk.class);
if (info.hasCustomMerge()) {
    Method method = info.mergeMethod();
    CustomChunk merged = (CustomChunk) method.invoke(acc, delta);
}
```

### 5. Record 지원

Java record도 동일하게 동작:

```java
public record Message(String role, String content) {}

TypeInfo info = TypeMetadataCache.getTypeInfo(Message.class);
// 일반 클래스와 동일하게 사용
```

---

## FieldMetadata 메서드

| 메서드 | 설명 |
|--------|------|
| `fieldName()` | 필드 이름 |
| `fieldType()` | 필드 타입 (Class) |
| `elementType()` | List일 경우 요소 타입 |
| `getValue(Object)` | 객체에서 값 읽기 |
| `isString()` | String 타입 여부 |
| `isNumber()` | Number 타입 여부 |
| `isList()` | List 타입 여부 |
| `isPrimitiveList()` | List<String>, List<Integer> 등 |
| `isObjectList()` | List<SomeObject> |
| `isObject()` | 중첩 객체 여부 |
| `isSpecialKey()` | "index" 또는 "type" 필드 여부 |

---

## TypeInfo 메서드

| 메서드 | 설명 |
|--------|------|
| `fields()` | 필드 메타데이터 리스트 |
| `indexFieldName()` | 인덱스 필드명 (없으면 null) |
| `mergeMethod()` | merge 메서드 (없으면 null) |
| `hasCustomMerge()` | merge 메서드 존재 여부 |
| `findField(String)` | 이름으로 필드 찾기 |

---

## 특징

- **스레드 세이프**: `ConcurrentHashMap` 사용
- **성능**: 클래스당 1회만 리플렉션, 이후 캐시에서 반환
- **Record 지원**: 일반 클래스와 record 모두 지원

---

## 설계 원칙

### Convention over Configuration

`index`라는 이름의 필드는 **자동으로 인식**됩니다.
`@StreamIndex`는 다른 이름을 쓸 때만 필요합니다.

```java
// Convention: 어노테이션 없이 동작
class Choice {
    Integer index;  // 자동 인식
}

// Configuration: 명시적 지정
class ToolCall {
    @StreamIndex
    Integer idx;    // 어노테이션 필요
}
```

### 외부 DTO 호환성

외부 라이브러리의 DTO도 수정 없이 사용 가능:

```java
// OpenAI SDK의 클래스 (수정 불가)
// index 필드가 있으면 그냥 동작함
TypeInfo info = TypeMetadataCache.getTypeInfo(ChatCompletionChunk.class);
```

---

## 참고 자료

### Java Annotation 학습

- [Oracle Java Tutorials - Annotations](https://docs.oracle.com/javase/tutorial/java/annotations/) - 공식 튜토리얼
- [Jenkov - Java Annotations](https://jenkov.com/tutorials/java/annotations.html) - 상세한 예제
- [GeeksforGeeks - Annotations in Java](https://www.geeksforgeeks.org/java/annotations-in-java/) - 입문자용

### Reflection & 메타데이터 패턴

- [Java Reflection Best Practices](https://www.javacodegeeks.com/2015/09/how-to-use-reflection-effectively.html) - 리플렉션 효율적 사용법
- [Metadata Mapping Pattern](https://java-design-patterns.com/patterns/metadata-mapping/) - 메타데이터 매핑 디자인 패턴
- [Patterns and Anti-Patterns in Annotation/Reflection](https://prgrmmng.com/patterns-anti-patterns-annotation-reflection-java) - 패턴과 안티패턴

### 핵심 원칙 요약

| 원칙 | 설명 |
|------|------|
| **캐시 필수** | 리플렉션 결과는 반드시 캐싱 |
| **초기화 시 실행** | 핫 패스에서 리플렉션 호출 금지 |
| **어노테이션 남용 금지** | 모든 것에 어노테이션 붙이지 말 것 |
| **setAccessible 최소화** | 꼭 필요한 경우에만 사용 |
