# Delta Stream

델타 스트리밍 객체를 하나의 누적된 결과로 병합하는 라이브러리.

## 개요

LLM API(OpenAI, Anthropic 등)의 스트리밍 응답처럼 부분적인 델타 객체가 순차적으로 도착할 때,
이를 하나의 완전한 객체로 조립하는 기능을 제공합니다.

## 기본 사용법

```java
DeltaMerger<ChatCompletionChunk> merger = new DeltaMerger<>(ChatCompletionChunk.class);

// 스트리밍으로 도착하는 델타들을 순차적으로 적용
for (ChatCompletionChunk delta : stream) {
    merger.applyDelta(delta);
}

// 최종 결과 빌드
ChatCompletionChunk result = merger.build();
```

## 병합 규칙

필드 타입에 따라 자동으로 적절한 병합 규칙이 적용됩니다.

### 기본 타입별 동작

| 타입                                | 동작                 | 예시                                     |
| ----------------------------------- | -------------------- | ---------------------------------------- |
| `String`                            | 연결 (concatenation) | `"Hello"` + `" world"` = `"Hello world"` |
| `Number` (Integer, Long, Double 등) | 합산 (addition)      | `10` + `5` = `15`                        |
| `Boolean`                           | 덮어쓰기 (overwrite) | `true` → `false` = `false`               |
| 객체                                | 재귀적 병합          | 각 필드에 동일 규칙 적용                 |

### 컬렉션 타입별 동작

| 타입                      | 동작                                      |
| ------------------------- | ----------------------------------------- |
| `List<기본타입>`          | 뒤에 추가 (append)                        |
| `List<객체>` (index 있음) | index 값으로 매칭하여 병합                |
| `List<객체>` (index 없음) | 뒤에 추가 (append)                        |
| `Array`                   | List와 동일                               |
| `Map<String, 기본타입>`   | key 기준 병합 (String concat, Number sum) |
| `Map<String, 객체>`       | key 기준 재귀 병합                        |

### null 값 처리

- `null` 값은 **"변경 없음"**으로 해석되어 무시됩니다.
- 단, `primitive` 타입(`int`, `boolean` 등)은 null을 표현할 수 없으므로 항상 덮어쓰기됩니다.

## 어노테이션

병합 동작을 커스터마이징하기 위한 어노테이션을 제공합니다.

### @StreamIndex

List/Array 요소 클래스에서 index 필드를 지정합니다.
같은 index 값을 가진 요소끼리 병합됩니다.

```java
public class Choice {
    @StreamIndex
    private int idx;      // 이 필드로 요소 매칭
    private String text;  // 같은 idx끼리 text가 연결됨
}
```

**참고:** `index`라는 이름의 필드는 어노테이션 없이도 자동 인식됩니다.

### @StreamList

List/Array 필드에서 요소의 index 필드명을 외부에서 지정합니다.
요소 클래스를 수정할 수 없을 때 유용합니다.

```java
public class Response {
    @StreamList("seq")  // ExternalItem.seq를 index로 사용
    private List<ExternalItem> items;
}
```

### @StreamOverwrite

기본 병합 규칙을 무시하고 항상 덮어쓰기합니다.

```java
public class ToolCall {
    @StreamOverwrite
    private String type;  // 연결 대신 덮어쓰기

    private String args;  // 기본 동작: 연결
}
```

## 커스텀 병합

클래스에 `T merge(T delta)` 메서드를 정의하면 해당 메서드가 병합에 사용됩니다.

```java
public class CustomChunk {
    private String content;

    // 커스텀 병합 로직
    public CustomChunk merge(CustomChunk delta) {
        return new CustomChunk(
            this.content + delta.content.toUpperCase()
        );
    }
}
```

이 규칙은 재귀적으로 적용됩니다:

- 중첩된 객체 필드
- List/Array 요소
- Map의 value

## Index 필드 우선순위

List/Array 요소의 index 필드는 다음 순서로 결정됩니다:

1. `@StreamList("fieldName")` - 리스트 필드에 지정
2. `@StreamIndex` - 요소 클래스에 지정
3. `"index"` - 관례적 필드명
4. 없으면 append 동작

## 지원 타입

- 일반 클래스 (기본 생성자 + getter / setter 필요)
- Record 클래스 (canonical 생성자 사용)
- 제네릭 상속 구조 (`Choice<T>` 등)

## 예시: OpenAI 스트리밍 응답

```java
@Getter
@Setter
@NoArgsConstructor
public class ChatCompletionChunk {
    private String id;
    private List<Choice> choices;
}

@Getter
@Setter
@NoArgsConstructor
public class Choice {
    private Integer index;  // 자동 인식되는 index 필드
    private Delta delta;
}

@Getter
@Setter
@NoArgsConstructor
public class Delta {
    @StreamOverwrite
    private String role; // 스트리밍에 따라 갱신
    private String content;  // 스트리밍으로 연결됨
}
```
