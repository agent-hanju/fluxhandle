package me.hanju.fluxhandle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lombok.Getter;
import lombok.Setter;
import me.hanju.streambind.annotation.StreamIndex;
import me.hanju.streambind.annotation.StreamList;
import me.hanju.streambind.annotation.StreamOverwrite;
import me.hanju.streambind.merge.StreamMerger;
import me.hanju.streambind.metadata.TypeMetadataCache;

class StreamMergerTest {

  @BeforeEach
  void setUp() {
    TypeMetadataCache.clearCache();
  }

  @Nested
  class BasicMergeRules {

    @Test
    void shouldAppendStringFields() {
      StreamMerger<SimpleDto> merger = new StreamMerger<>(SimpleDto.class);

      SimpleDto delta1 = new SimpleDto();
      delta1.content = "Hello";
      merger.applyDelta(delta1);

      SimpleDto delta2 = new SimpleDto();
      delta2.content = " world";
      merger.applyDelta(delta2);

      SimpleDto result = merger.build();
      assertEquals("Hello world", result.content);
    }

    @Test
    void shouldSumIntegerFields() {
      StreamMerger<SimpleDto> merger = new StreamMerger<>(SimpleDto.class);

      SimpleDto delta1 = new SimpleDto();
      delta1.count = 10;
      merger.applyDelta(delta1);

      SimpleDto delta2 = new SimpleDto();
      delta2.count = 5;
      merger.applyDelta(delta2);

      SimpleDto result = merger.build();
      assertEquals(15, result.count);
    }

    @Test
    void shouldSumLongFields() {
      StreamMerger<SimpleDto> merger = new StreamMerger<>(SimpleDto.class);

      SimpleDto delta1 = new SimpleDto();
      delta1.timestamp = 1000L;
      merger.applyDelta(delta1);

      SimpleDto delta2 = new SimpleDto();
      delta2.timestamp = 500L;
      merger.applyDelta(delta2);

      SimpleDto result = merger.build();
      assertEquals(1500L, result.timestamp);
    }

    @Test
    void shouldSumDoubleFields() {
      StreamMerger<NumberDto> merger = new StreamMerger<>(NumberDto.class);

      NumberDto delta1 = new NumberDto();
      delta1.price = 10.5;
      merger.applyDelta(delta1);

      NumberDto delta2 = new NumberDto();
      delta2.price = 5.3;
      merger.applyDelta(delta2);

      NumberDto result = merger.build();
      assertEquals(15.8, result.price, 0.001);
    }

    @Test
    void shouldRecognizeConventionIndexField() {
      // "index"라는 이름의 필드는 @StreamIndex 없이도 자동 인식
      StreamMerger<ConventionIndexItem> merger = new StreamMerger<>(ConventionIndexItem.class);

      ConventionIndexItem delta1 = new ConventionIndexItem();
      delta1.index = 0;  // 덮어쓰기 대상
      delta1.data = "Hello";
      merger.applyDelta(delta1);

      ConventionIndexItem delta2 = new ConventionIndexItem();
      delta2.index = 99;  // 덮어쓰기됨
      delta2.data = " world";
      merger.applyDelta(delta2);

      ConventionIndexItem result = merger.build();
      assertEquals(99, result.index);  // 덮어쓰기
      assertEquals("Hello world", result.data);  // 연결
    }

    @Test
    void shouldReturnEmptyObjectWhenNoDeltaApplied() {
      StreamMerger<SimpleDto> merger = new StreamMerger<>(SimpleDto.class);

      SimpleDto result = merger.build();
      assertNotNull(result);
      assertNull(result.content);
      assertNull(result.count);
    }

    @Test
    void shouldReplaceSpecialKeyIndex() {
      StreamMerger<IndexedItem> merger = new StreamMerger<>(IndexedItem.class);

      IndexedItem delta1 = new IndexedItem();
      delta1.index = 0;
      delta1.value = "first";
      merger.applyDelta(delta1);

      IndexedItem delta2 = new IndexedItem();
      delta2.index = 0;
      delta2.value = " second";
      merger.applyDelta(delta2);

      IndexedItem result = merger.build();
      assertEquals(0, result.index);
      assertEquals("first second", result.value);
    }

    @Test
    void shouldReplaceStreamOverwriteField() {
      StreamMerger<TypedItem> merger = new StreamMerger<>(TypedItem.class);

      TypedItem delta1 = new TypedItem();
      delta1.type = "function";
      delta1.name = "get";
      merger.applyDelta(delta1);

      TypedItem delta2 = new TypedItem();
      delta2.type = "tool";  // @StreamOverwrite로 덮어쓰기
      delta2.name = "_weather";
      merger.applyDelta(delta2);

      TypedItem result = merger.build();
      assertEquals("tool", result.type);  // 덮어쓰기됨
      assertEquals("get_weather", result.name);  // String은 연결
    }

    @Test
    void shouldSkipNullOnStreamOverwriteField() {
      // @StreamOverwrite 필드에서도 null은 무시되어 기존 값 유지
      StreamMerger<TypedItem> merger = new StreamMerger<>(TypedItem.class);

      TypedItem delta1 = new TypedItem();
      delta1.type = "function";
      delta1.name = "get";
      merger.applyDelta(delta1);

      TypedItem delta2 = new TypedItem();
      delta2.type = null;  // null → 기존 값 유지
      delta2.name = "_weather";
      merger.applyDelta(delta2);

      TypedItem result = merger.build();
      assertEquals("function", result.type);  // null이므로 기존 값 유지
      assertEquals("get_weather", result.name);
    }

    @Test
    void shouldReplaceStreamOverwriteListField() {
      // @StreamOverwrite가 List 필드에 있으면 병합 없이 전체 덮어쓰기
      StreamMerger<OverwriteListContainer> merger =
          new StreamMerger<>(OverwriteListContainer.class);

      // 첫 번째 델타: tags = ["a", "b"]
      OverwriteListContainer delta1 = new OverwriteListContainer();
      delta1.tags = new ArrayList<>(List.of("a", "b"));
      merger.applyDelta(delta1);

      // 두 번째 델타: tags = ["c"] → 전체 덮어쓰기
      OverwriteListContainer delta2 = new OverwriteListContainer();
      delta2.tags = new ArrayList<>(List.of("c"));
      merger.applyDelta(delta2);

      OverwriteListContainer result = merger.build();
      // 일반 List면 ["a", "b", "c"]가 되겠지만, @StreamOverwrite이므로 ["c"]
      assertEquals(1, result.tags.size());
      assertEquals("c", result.tags.get(0));
    }

    @Test
    void shouldReplaceStreamOverwriteObjectListField() {
      // @StreamOverwrite가 객체 List 필드에 있으면 index 기반 병합 없이 전체 덮어쓰기
      StreamMerger<OverwriteObjectListContainer> merger =
          new StreamMerger<>(OverwriteObjectListContainer.class);

      // 첫 번째 델타: items = [{index:0, value:"first"}]
      OverwriteObjectListContainer delta1 = new OverwriteObjectListContainer();
      delta1.items = new ArrayList<>();
      IndexedItem item1 = new IndexedItem();
      item1.index = 0;
      item1.value = "first";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      // 두 번째 델타: items = [{index:0, value:"second"}] → 전체 덮어쓰기
      OverwriteObjectListContainer delta2 = new OverwriteObjectListContainer();
      delta2.items = new ArrayList<>();
      IndexedItem item2 = new IndexedItem();
      item2.index = 0;
      item2.value = "second";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      OverwriteObjectListContainer result = merger.build();
      // 일반 List면 index 기반 병합되어 value="first second"가 되겠지만,
      // @StreamOverwrite이므로 두 번째 델타로 전체 대체됨
      assertEquals(1, result.items.size());
      assertEquals(0, result.items.get(0).index);
      assertEquals("second", result.items.get(0).value);  // 병합 없음, 덮어쓰기
    }

    @Test
    void shouldSkipNullDeltaValues() {
      StreamMerger<SimpleDto> merger = new StreamMerger<>(SimpleDto.class);

      SimpleDto delta1 = new SimpleDto();
      delta1.content = "Hello";
      delta1.count = 10;
      merger.applyDelta(delta1);

      SimpleDto delta2 = new SimpleDto();
      delta2.content = " world";
      // count is null - should not change
      merger.applyDelta(delta2);

      SimpleDto result = merger.build();
      assertEquals("Hello world", result.content);
      assertEquals(10, result.count);
    }

    @Test
    void shouldSkipNullDelta() {
      StreamMerger<SimpleDto> merger = new StreamMerger<>(SimpleDto.class);

      SimpleDto delta1 = new SimpleDto();
      delta1.content = "Hello";
      merger.applyDelta(delta1);

      merger.applyDelta(null);

      SimpleDto result = merger.build();
      assertEquals("Hello", result.content);
    }

    @Test
    void shouldOverwritePrimitiveFields() {
      // primitive 타입은 null 표현 불가 → 항상 덮어쓰기
      StreamMerger<PrimitiveDto> merger = new StreamMerger<>(PrimitiveDto.class);

      PrimitiveDto delta1 = new PrimitiveDto();
      delta1.setAge(10);
      delta1.setScore(3.5);
      delta1.setActive(true);
      merger.applyDelta(delta1);

      PrimitiveDto delta2 = new PrimitiveDto();
      delta2.setAge(5);       // 덮어쓰기 (합산 X)
      delta2.setScore(1.5);   // 덮어쓰기 (합산 X)
      delta2.setActive(false);
      merger.applyDelta(delta2);

      PrimitiveDto result = merger.build();
      assertEquals(5, result.getAge());             // 덮어쓰기됨
      assertEquals(1.5, result.getScore(), 0.001);  // 덮어쓰기됨
      assertFalse(result.isActive());               // 덮어쓰기됨
    }
  }

  @Nested
  class NestedObjectMerge {

    @Test
    void shouldMergeNestedObjects() {
      StreamMerger<Parent> merger = new StreamMerger<>(Parent.class);

      Parent delta1 = new Parent();
      delta1.id = "parent-1";
      delta1.child = new SimpleDto();
      delta1.child.content = "Hello";
      merger.applyDelta(delta1);

      Parent delta2 = new Parent();
      delta2.child = new SimpleDto();
      delta2.child.content = " world";
      merger.applyDelta(delta2);

      Parent result = merger.build();
      assertEquals("parent-1", result.id);
      assertEquals("Hello world", result.child.content);
    }

    @Test
    void shouldMergeDeeplyNestedObjects() {
      StreamMerger<GrandParent> merger = new StreamMerger<>(GrandParent.class);

      GrandParent delta1 = new GrandParent();
      delta1.name = "root";
      delta1.parent = new Parent();
      delta1.parent.id = "parent";
      delta1.parent.child = new SimpleDto();
      delta1.parent.child.content = "Hello";
      merger.applyDelta(delta1);

      GrandParent delta2 = new GrandParent();
      delta2.parent = new Parent();
      delta2.parent.child = new SimpleDto();
      delta2.parent.child.content = " world";
      merger.applyDelta(delta2);

      GrandParent result = merger.build();
      assertEquals("root", result.name);
      assertEquals("parent", result.parent.id);
      assertEquals("Hello world", result.parent.child.content);
    }
  }

  @Nested
  class ListMerge {

    @Test
    void shouldExtendPrimitiveStringList() {
      StreamMerger<ListContainer> merger = new StreamMerger<>(ListContainer.class);

      ListContainer delta1 = new ListContainer();
      delta1.tags = new ArrayList<>(List.of("java", "kotlin"));
      merger.applyDelta(delta1);

      ListContainer delta2 = new ListContainer();
      delta2.tags = new ArrayList<>(List.of("scala"));
      merger.applyDelta(delta2);

      ListContainer result = merger.build();
      assertEquals(List.of("java", "kotlin", "scala"), result.tags);
    }

    @Test
    void shouldMergeObjectListByIndex() {
      StreamMerger<ListContainer> merger = new StreamMerger<>(ListContainer.class);

      ListContainer delta1 = new ListContainer();
      delta1.items = new ArrayList<>();
      IndexedItem item1 = new IndexedItem();
      item1.index = 0;
      item1.value = "Hello";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      ListContainer delta2 = new ListContainer();
      delta2.items = new ArrayList<>();
      IndexedItem item2 = new IndexedItem();
      item2.index = 0;
      item2.value = " world";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      ListContainer result = merger.build();
      assertEquals(1, result.items.size());
      assertEquals(0, result.items.get(0).index);
      assertEquals("Hello world", result.items.get(0).value);
    }

    @Test
    void shouldAddNewIndexToObjectList() {
      StreamMerger<ListContainer> merger = new StreamMerger<>(ListContainer.class);

      ListContainer delta1 = new ListContainer();
      delta1.items = new ArrayList<>();
      IndexedItem item1 = new IndexedItem();
      item1.index = 0;
      item1.value = "first";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      ListContainer delta2 = new ListContainer();
      delta2.items = new ArrayList<>();
      IndexedItem item2 = new IndexedItem();
      item2.index = 1;
      item2.value = "second";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      ListContainer result = merger.build();
      assertEquals(2, result.items.size());
      assertEquals("first", result.items.get(0).value);
      assertEquals("second", result.items.get(1).value);
    }

    @Test
    void shouldMergeInterleavedItems() {
      StreamMerger<ListContainer> merger = new StreamMerger<>(ListContainer.class);

      // 3개 인덱스가 무작위 순서로 교차 도착하는 시나리오
      // index 0: "He" + "llo" + "!" = "Hello!"
      // index 1: "Wo" + "rld" = "World"
      // index 2: "테" + "스" + "트" = "테스트"
      int[] indices =  {0,    1,    2,    0,     1,     2,   0,   2};
      String[] values = {"He", "Wo", "테", "llo", "rld", "스", "!", "트"};

      for (int i = 0; i < indices.length; i++) {
        ListContainer delta = new ListContainer();
        delta.items = new ArrayList<>();
        IndexedItem item = new IndexedItem();
        item.index = indices[i];
        item.value = values[i];
        delta.items.add(item);
        merger.applyDelta(delta);
      }

      ListContainer result = merger.build();
      assertEquals(3, result.items.size());
      assertEquals("Hello!", result.items.get(0).value);
      assertEquals("World", result.items.get(1).value);
      assertEquals("테스트", result.items.get(2).value);
    }

    @Test
    void shouldHandleMultipleItemsInSingleDelta() {
      // 한 번의 delta에 여러 인덱스의 아이템이 들어오는 경우
      StreamMerger<ListContainer> merger = new StreamMerger<>(ListContainer.class);

      // 첫 delta: index 0, 1, 2 세 개의 아이템이 한꺼번에
      ListContainer delta1 = new ListContainer();
      delta1.items = new ArrayList<>();

      IndexedItem item0 = new IndexedItem();
      item0.index = 0;
      item0.value = "Hello";
      delta1.items.add(item0);

      IndexedItem item1 = new IndexedItem();
      item1.index = 1;
      item1.value = "World";
      delta1.items.add(item1);

      IndexedItem item2 = new IndexedItem();
      item2.index = 2;
      item2.value = "Test";
      delta1.items.add(item2);

      merger.applyDelta(delta1);

      // 두 번째 delta: index 0, 2에 추가 (1은 건드리지 않음)
      ListContainer delta2 = new ListContainer();
      delta2.items = new ArrayList<>();

      IndexedItem item0_2 = new IndexedItem();
      item0_2.index = 0;
      item0_2.value = "!";
      delta2.items.add(item0_2);

      IndexedItem item2_2 = new IndexedItem();
      item2_2.index = 2;
      item2_2.value = "ing";
      delta2.items.add(item2_2);

      merger.applyDelta(delta2);

      ListContainer result = merger.build();
      assertEquals(3, result.items.size());
      assertEquals("Hello!", result.items.get(0).value);
      assertEquals("World", result.items.get(1).value);  // 변경 없음
      assertEquals("Testing", result.items.get(2).value);
    }
  }

  @Nested
  class RecordSupport {

    @Test
    void shouldBuildRecordType() {
      StreamMerger<SimpleRecord> merger = new StreamMerger<>(SimpleRecord.class);

      SimpleRecord delta1 = new SimpleRecord("Hello", 10);
      merger.applyDelta(delta1);

      SimpleRecord delta2 = new SimpleRecord(" world", 5);
      merger.applyDelta(delta2);

      SimpleRecord result = merger.build();
      assertEquals("Hello world", result.content());
      assertEquals(15, result.count());
    }

    @Test
    void shouldMergeNestedRecords() {
      StreamMerger<ParentRecord> merger = new StreamMerger<>(ParentRecord.class);

      ParentRecord delta1 = new ParentRecord("parent", new SimpleRecord("Hello", 10));
      merger.applyDelta(delta1);

      ParentRecord delta2 = new ParentRecord(null, new SimpleRecord(" world", 5));
      merger.applyDelta(delta2);

      ParentRecord result = merger.build();
      assertEquals("parent", result.id());
      assertEquals("Hello world", result.child().content());
      assertEquals(15, result.child().count());
    }
  }

  @Nested
  class CustomMergeMethod {

    @Test
    void shouldUseCustomMergeMethod() {
      StreamMerger<CustomChunk> merger = new StreamMerger<>(CustomChunk.class);

      merger.applyDelta(new CustomChunk("Hello"));
      merger.applyDelta(new CustomChunk(" "));
      merger.applyDelta(new CustomChunk("world"));

      CustomChunk result = merger.build();
      assertEquals("Hello world", result.getContent());
    }

    @Test
    void shouldHandleSingleDeltaWithCustomMerge() {
      StreamMerger<CustomChunk> merger = new StreamMerger<>(CustomChunk.class);

      merger.applyDelta(new CustomChunk("only one"));

      CustomChunk result = merger.build();
      assertEquals("only one", result.getContent());
    }

    @Test
    void shouldUseNestedCustomMergeMethod() {
      // 중첩된 객체 필드에서 customMerge 사용
      StreamMerger<NestedCustomMergeContainer> merger =
          new StreamMerger<>(NestedCustomMergeContainer.class);

      NestedCustomMergeContainer delta1 = new NestedCustomMergeContainer();
      delta1.name = "Container";
      delta1.chunk = new CustomChunk("Hello");
      merger.applyDelta(delta1);

      NestedCustomMergeContainer delta2 = new NestedCustomMergeContainer();
      delta2.name = " One";  // String 연결
      delta2.chunk = new CustomChunk(" world");  // customMerge 호출
      merger.applyDelta(delta2);

      NestedCustomMergeContainer result = merger.build();
      assertEquals("Container One", result.name);
      assertEquals("Hello world", result.chunk.getContent());
    }

    @Test
    void shouldUseCustomMergeInList() {
      // List 요소에서 customMerge 사용
      StreamMerger<CustomMergeListContainer> merger =
          new StreamMerger<>(CustomMergeListContainer.class);

      CustomMergeListContainer delta1 = new CustomMergeListContainer();
      delta1.items = new ArrayList<>();
      delta1.items.add(new IndexedCustomChunk(0, "Hello"));
      merger.applyDelta(delta1);

      CustomMergeListContainer delta2 = new CustomMergeListContainer();
      delta2.items = new ArrayList<>();
      delta2.items.add(new IndexedCustomChunk(0, " world"));  // 같은 index → merge() 호출
      delta2.items.add(new IndexedCustomChunk(1, "New"));     // 새 index
      merger.applyDelta(delta2);

      CustomMergeListContainer result = merger.build();
      assertEquals(2, result.items.size());
      assertEquals("Hello world", result.items.get(0).getContent());
      assertEquals("New", result.items.get(1).getContent());
    }
  }

  @Nested
  class OpenAIScenario {

    @Test
    void shouldAssembleTextResponse() {
      StreamMerger<ChatCompletionChunk> merger =
          new StreamMerger<>(ChatCompletionChunk.class);

      // Chunk 1: metadata + role
      ChatCompletionChunk chunk1 = new ChatCompletionChunk();
      chunk1.id = "chatcmpl-123";
      chunk1.choices = new ArrayList<>();
      Choice choice1 = new Choice();
      choice1.index = 0;
      choice1.delta = new Delta();
      choice1.delta.role = "assistant";
      choice1.delta.content = "";
      chunk1.choices.add(choice1);
      merger.applyDelta(chunk1);

      // Chunk 2: content part 1
      ChatCompletionChunk chunk2 = new ChatCompletionChunk();
      chunk2.choices = new ArrayList<>();
      Choice choice2 = new Choice();
      choice2.index = 0;
      choice2.delta = new Delta();
      choice2.delta.content = "Hello";
      chunk2.choices.add(choice2);
      merger.applyDelta(chunk2);

      // Chunk 3: content part 2
      ChatCompletionChunk chunk3 = new ChatCompletionChunk();
      chunk3.choices = new ArrayList<>();
      Choice choice3 = new Choice();
      choice3.index = 0;
      choice3.delta = new Delta();
      choice3.delta.content = ", world!";
      chunk3.choices.add(choice3);
      merger.applyDelta(chunk3);

      ChatCompletionChunk result = merger.build();
      assertEquals("chatcmpl-123", result.id);
      assertEquals(1, result.choices.size());
      assertEquals(0, result.choices.get(0).index);
      assertEquals("assistant", result.choices.get(0).delta.role);
      assertEquals("Hello, world!", result.choices.get(0).delta.content);
    }

    @Test
    void shouldAssembleToolCallResponse() {
      StreamMerger<ChatCompletionChunk> merger =
          new StreamMerger<>(ChatCompletionChunk.class);

      // Chunk 1: tool call start
      ChatCompletionChunk chunk1 = new ChatCompletionChunk();
      chunk1.id = "chatcmpl-456";
      chunk1.choices = new ArrayList<>();
      Choice choice1 = new Choice();
      choice1.index = 0;
      choice1.delta = new Delta();
      choice1.delta.toolCalls = new ArrayList<>();
      ToolCall tc1 = new ToolCall();
      tc1.index = 0;
      tc1.id = "call_abc";
      tc1.type = "function";
      tc1.function = new FunctionCall();
      tc1.function.name = "get_weather";
      tc1.function.arguments = "";
      choice1.delta.toolCalls.add(tc1);
      chunk1.choices.add(choice1);
      merger.applyDelta(chunk1);

      // Chunk 2: arguments part 1
      ChatCompletionChunk chunk2 = new ChatCompletionChunk();
      chunk2.choices = new ArrayList<>();
      Choice choice2 = new Choice();
      choice2.index = 0;
      choice2.delta = new Delta();
      choice2.delta.toolCalls = new ArrayList<>();
      ToolCall tc2 = new ToolCall();
      tc2.index = 0;
      tc2.function = new FunctionCall();
      tc2.function.arguments = "{\"location\"";
      choice2.delta.toolCalls.add(tc2);
      chunk2.choices.add(choice2);
      merger.applyDelta(chunk2);

      // Chunk 3: arguments part 2
      ChatCompletionChunk chunk3 = new ChatCompletionChunk();
      chunk3.choices = new ArrayList<>();
      Choice choice3 = new Choice();
      choice3.index = 0;
      choice3.delta = new Delta();
      choice3.delta.toolCalls = new ArrayList<>();
      ToolCall tc3 = new ToolCall();
      tc3.index = 0;
      tc3.function = new FunctionCall();
      tc3.function.arguments = ":\"Seoul\"}";
      choice3.delta.toolCalls.add(tc3);
      chunk3.choices.add(choice3);
      merger.applyDelta(chunk3);

      ChatCompletionChunk result = merger.build();
      assertEquals("chatcmpl-456", result.id);
      ToolCall resultTc = result.choices.get(0).delta.toolCalls.get(0);
      assertEquals("call_abc", resultTc.id);
      assertEquals("function", resultTc.type);
      assertEquals("get_weather", resultTc.function.name);
      assertEquals("{\"location\":\"Seoul\"}", resultTc.function.arguments);
    }

    @Test
    void shouldAssembleMultipleToolCalls() {
      StreamMerger<ChatCompletionChunk> merger =
          new StreamMerger<>(ChatCompletionChunk.class);

      // Chunk 1: first tool call
      ChatCompletionChunk chunk1 = new ChatCompletionChunk();
      chunk1.choices = new ArrayList<>();
      Choice choice1 = new Choice();
      choice1.index = 0;
      choice1.delta = new Delta();
      choice1.delta.toolCalls = new ArrayList<>();
      ToolCall tc1 = new ToolCall();
      tc1.index = 0;
      tc1.id = "call_1";
      tc1.function = new FunctionCall();
      tc1.function.name = "func1";
      tc1.function.arguments = "{\"a\":";
      choice1.delta.toolCalls.add(tc1);
      chunk1.choices.add(choice1);
      merger.applyDelta(chunk1);

      // Chunk 2: second tool call starts
      ChatCompletionChunk chunk2 = new ChatCompletionChunk();
      chunk2.choices = new ArrayList<>();
      Choice choice2 = new Choice();
      choice2.index = 0;
      choice2.delta = new Delta();
      choice2.delta.toolCalls = new ArrayList<>();
      ToolCall tc2 = new ToolCall();
      tc2.index = 1;
      tc2.id = "call_2";
      tc2.function = new FunctionCall();
      tc2.function.name = "func2";
      tc2.function.arguments = "{\"b\":";
      choice2.delta.toolCalls.add(tc2);
      chunk2.choices.add(choice2);
      merger.applyDelta(chunk2);

      // Chunk 3: first tool call continues
      ChatCompletionChunk chunk3 = new ChatCompletionChunk();
      chunk3.choices = new ArrayList<>();
      Choice choice3 = new Choice();
      choice3.index = 0;
      choice3.delta = new Delta();
      choice3.delta.toolCalls = new ArrayList<>();
      ToolCall tc3 = new ToolCall();
      tc3.index = 0;
      tc3.function = new FunctionCall();
      tc3.function.arguments = "1}";
      choice3.delta.toolCalls.add(tc3);
      chunk3.choices.add(choice3);
      merger.applyDelta(chunk3);

      // Chunk 4: second tool call continues
      ChatCompletionChunk chunk4 = new ChatCompletionChunk();
      chunk4.choices = new ArrayList<>();
      Choice choice4 = new Choice();
      choice4.index = 0;
      choice4.delta = new Delta();
      choice4.delta.toolCalls = new ArrayList<>();
      ToolCall tc4 = new ToolCall();
      tc4.index = 1;
      tc4.function = new FunctionCall();
      tc4.function.arguments = "2}";
      choice4.delta.toolCalls.add(tc4);
      chunk4.choices.add(choice4);
      merger.applyDelta(chunk4);

      ChatCompletionChunk result = merger.build();
      List<ToolCall> toolCalls = result.choices.get(0).delta.toolCalls;
      assertEquals(2, toolCalls.size());
      assertEquals("call_1", toolCalls.get(0).id);
      assertEquals("{\"a\":1}", toolCalls.get(0).function.arguments);
      assertEquals("call_2", toolCalls.get(1).id);
      assertEquals("{\"b\":2}", toolCalls.get(1).function.arguments);
    }
  }

  @Nested
  class StreamIndexTypeSupport {

    @Test
    void shouldSupportPrimitiveIntIndex() {
      // @StreamIndex가 int (primitive) 타입에서 동작하는지 확인
      StreamMerger<ListWithPrimitiveIndex> merger =
          new StreamMerger<>(ListWithPrimitiveIndex.class);

      ListWithPrimitiveIndex delta1 = new ListWithPrimitiveIndex();
      delta1.items = new ArrayList<>();
      PrimitiveIntIndexItem item1 = new PrimitiveIntIndexItem();
      item1.idx = 0;
      item1.value = "Hello";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      ListWithPrimitiveIndex delta2 = new ListWithPrimitiveIndex();
      delta2.items = new ArrayList<>();
      PrimitiveIntIndexItem item2 = new PrimitiveIntIndexItem();
      item2.idx = 0;
      item2.value = " world";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      ListWithPrimitiveIndex result = merger.build();
      assertEquals(1, result.items.size());
      assertEquals(0, result.items.get(0).idx);
      assertEquals("Hello world", result.items.get(0).value);
    }

    @Test
    void shouldAppendWhenStringIndexField() {
      // @StreamIndex가 String 필드에 붙어있으면 무시됨 (int/Integer만 지원)
      // index 필드가 없는 것으로 처리되어 append 동작
      StreamMerger<ListWithStringIndex> merger =
          new StreamMerger<>(ListWithStringIndex.class);

      ListWithStringIndex delta1 = new ListWithStringIndex();
      delta1.items = new ArrayList<>();
      StringIndexItem item1 = new StringIndexItem();
      item1.idx = "0";
      item1.value = "Hello";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      ListWithStringIndex delta2 = new ListWithStringIndex();
      delta2.items = new ArrayList<>();
      StringIndexItem item2 = new StringIndexItem();
      item2.idx = "0";
      item2.value = " world";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      ListWithStringIndex result = merger.build();
      // index 필드 없음으로 처리 → append → 2개의 아이템
      assertEquals(2, result.items.size());
      assertEquals("Hello", result.items.get(0).value);
      assertEquals(" world", result.items.get(1).value);
    }

    @Test
    void shouldAppendWhenStringFieldNamedIndex() {
      // "index"라는 이름이지만 String 타입이면 무시됨 (int/Integer만 지원)
      // index 필드가 없는 것으로 처리되어 append 동작
      StreamMerger<ListWithStringNamedIndex> merger =
          new StreamMerger<>(ListWithStringNamedIndex.class);

      ListWithStringNamedIndex delta1 = new ListWithStringNamedIndex();
      delta1.items = new ArrayList<>();
      StringNamedIndexItem item1 = new StringNamedIndexItem();
      item1.index = "0";
      item1.value = "Hello";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      ListWithStringNamedIndex delta2 = new ListWithStringNamedIndex();
      delta2.items = new ArrayList<>();
      StringNamedIndexItem item2 = new StringNamedIndexItem();
      item2.index = "0";
      item2.value = " world";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      ListWithStringNamedIndex result = merger.build();
      // index 필드 없음으로 처리 → append → 2개의 아이템
      assertEquals(2, result.items.size());
      assertEquals("Hello", result.items.get(0).value);
      assertEquals(" world", result.items.get(1).value);
    }

    @Test
    void shouldAppendWhenNoIndexField() {
      // index 필드가 없는 객체 리스트는 append 동작
      StreamMerger<BadListContainer> merger =
          new StreamMerger<>(BadListContainer.class);

      BadListContainer delta1 = new BadListContainer();
      delta1.items = new ArrayList<>();
      NoIndexItem item1 = new NoIndexItem();
      item1.value = "first";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      BadListContainer delta2 = new BadListContainer();
      delta2.items = new ArrayList<>();
      NoIndexItem item2 = new NoIndexItem();
      item2.value = "second";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      BadListContainer result = merger.build();
      // index 필드 없음 → append → 2개의 아이템
      assertEquals(2, result.items.size());
      assertEquals("first", result.items.get(0).value);
      assertEquals("second", result.items.get(1).value);
    }
  }

  @Nested
  class ExceptionCases {

    @Test
    void shouldThrowOnNullType() {
      assertThrows(IllegalArgumentException.class, () ->
          new StreamMerger<>(null));
    }
  }

  /**
   * @StreamList 어노테이션 테스트.
   * 리스트 필드에서 인덱스 필드명을 외부에서 지정하는 기능을 검증한다.
   */
  @Nested
  class StreamListAnnotation {

    @Test
    void shouldUseStreamListValueAttribute() {
      // @StreamList(index = "seq")로 인덱스 필드 지정
      StreamMerger<StreamListContainer> merger =
          new StreamMerger<>(StreamListContainer.class);

      StreamListContainer delta1 = new StreamListContainer();
      delta1.items = new ArrayList<>();
      ExternalItem item1 = new ExternalItem();
      item1.seq = 0;
      item1.content = "Hello";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      StreamListContainer delta2 = new StreamListContainer();
      delta2.items = new ArrayList<>();
      ExternalItem item2 = new ExternalItem();
      item2.seq = 0;
      item2.content = " world";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      StreamListContainer result = merger.build();
      assertEquals(1, result.items.size());
      assertEquals(0, result.items.get(0).seq);
      assertEquals("Hello world", result.items.get(0).content);
    }

    @Test
    void shouldUseStreamListIndexAttribute() {
      // @StreamList(index = "seq")로 인덱스 필드 지정
      StreamMerger<StreamListIndexAttrContainer> merger =
          new StreamMerger<>(StreamListIndexAttrContainer.class);

      StreamListIndexAttrContainer delta1 = new StreamListIndexAttrContainer();
      delta1.items = new ArrayList<>();
      ExternalItem item1 = new ExternalItem();
      item1.seq = 0;
      item1.content = "First";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      StreamListIndexAttrContainer delta2 = new StreamListIndexAttrContainer();
      delta2.items = new ArrayList<>();
      ExternalItem item2 = new ExternalItem();
      item2.seq = 0;
      item2.content = " Second";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      StreamListIndexAttrContainer result = merger.build();
      assertEquals(1, result.items.size());
      assertEquals("First Second", result.items.get(0).content);
    }

    @Test
    void shouldAppendWhenStreamListFieldNotExists() {
      // @StreamList(index = "nonExistent")로 존재하지 않는 필드 지정 → append
      StreamMerger<StreamListInvalidFieldContainer> merger =
          new StreamMerger<>(StreamListInvalidFieldContainer.class);

      StreamListInvalidFieldContainer delta1 = new StreamListInvalidFieldContainer();
      delta1.items = new ArrayList<>();
      ExternalItem item1 = new ExternalItem();
      item1.seq = 0;
      item1.content = "First";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      StreamListInvalidFieldContainer delta2 = new StreamListInvalidFieldContainer();
      delta2.items = new ArrayList<>();
      ExternalItem item2 = new ExternalItem();
      item2.seq = 0;
      item2.content = "Second";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      StreamListInvalidFieldContainer result = merger.build();
      // 존재하지 않는 필드 → append
      assertEquals(2, result.items.size());
      assertEquals("First", result.items.get(0).content);
      assertEquals("Second", result.items.get(1).content);
    }

    @Test
    void shouldAppendWhenStreamListFieldIsNotInteger() {
      // @StreamList(index = "seq")로 String 타입 필드 지정 → append
      StreamMerger<StreamListStringFieldContainer> merger =
          new StreamMerger<>(StreamListStringFieldContainer.class);

      StreamListStringFieldContainer delta1 = new StreamListStringFieldContainer();
      delta1.items = new ArrayList<>();
      StreamListStringFieldItem item1 = new StreamListStringFieldItem();
      item1.seq = "0";
      item1.content = "First";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      StreamListStringFieldContainer delta2 = new StreamListStringFieldContainer();
      delta2.items = new ArrayList<>();
      StreamListStringFieldItem item2 = new StreamListStringFieldItem();
      item2.seq = "0";
      item2.content = "Second";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      StreamListStringFieldContainer result = merger.build();
      // String 타입 → int/Integer가 아님 → append
      assertEquals(2, result.items.size());
      assertEquals("First", result.items.get(0).content);
      assertEquals("Second", result.items.get(1).content);
    }

    @Test
    void shouldOverrideStreamIndexWithStreamList() {
      // @StreamList(index = "customIdx")가 요소 클래스의 @StreamIndex보다 우선
      // streambind는 index 값을 배열 위치로 사용함
      StreamMerger<StreamListOverrideContainer> merger =
          new StreamMerger<>(StreamListOverrideContainer.class);

      // 첫 번째 델타: index=0, customIdx=1
      StreamListOverrideContainer delta1 = new StreamListOverrideContainer();
      delta1.items = new ArrayList<>();
      ItemWithStreamIndex item1 = new ItemWithStreamIndex();
      item1.index = 0;
      item1.customIdx = 1;  // @StreamList가 사용할 인덱스
      item1.value = "Custom";
      delta1.items.add(item1);
      merger.applyDelta(delta1);

      // 두 번째 델타: index=0 (같음), customIdx=1 (같음) → 병합
      StreamListOverrideContainer delta2 = new StreamListOverrideContainer();
      delta2.items = new ArrayList<>();
      ItemWithStreamIndex item2 = new ItemWithStreamIndex();
      item2.index = 0;
      item2.customIdx = 1;
      item2.value = " Index";
      delta2.items.add(item2);
      merger.applyDelta(delta2);

      // 세 번째 델타: index=0 (같음), customIdx=2 (다름) → 새 아이템
      StreamListOverrideContainer delta3 = new StreamListOverrideContainer();
      delta3.items = new ArrayList<>();
      ItemWithStreamIndex item3 = new ItemWithStreamIndex();
      item3.index = 0;  // @StreamIndex라면 병합되어야 함
      item3.customIdx = 2;  // @StreamList로 인해 새 아이템
      item3.value = "New";
      delta3.items.add(item3);
      merger.applyDelta(delta3);

      StreamListOverrideContainer result = merger.build();
      // @StreamList(index = "customIdx")가 우선 → customIdx 기준으로 병합
      // index 값이 배열 위치로 사용됨: [0]=null, [1]=병합된 아이템, [2]=새 아이템
      assertEquals(3, result.items.size());
      assertNull(result.items.get(0));  // position 0은 사용 안 됨
      assertEquals(1, result.items.get(1).customIdx);
      assertEquals("Custom Index", result.items.get(1).value);
      assertEquals(2, result.items.get(2).customIdx);
      assertEquals("New", result.items.get(2).value);
    }
  }

  /**
   * 배열 타입 지원 테스트.
   * List와 동일한 병합 규칙이 Array에도 적용되는지 검증한다.
   */
  @Nested
  class ArraySupport {

    @Test
    void shouldAppendStringArray() {
      // String[] 배열 append 병합
      StreamMerger<StringArrayContainer> merger =
          new StreamMerger<>(StringArrayContainer.class);

      StringArrayContainer delta1 = new StringArrayContainer();
      delta1.tags = new String[]{"a", "b"};
      merger.applyDelta(delta1);

      StringArrayContainer delta2 = new StringArrayContainer();
      delta2.tags = new String[]{"c"};
      merger.applyDelta(delta2);

      StringArrayContainer result = merger.build();
      assertEquals(3, result.tags.length);
      assertEquals("a", result.tags[0]);
      assertEquals("b", result.tags[1]);
      assertEquals("c", result.tags[2]);
    }

    @Test
    void shouldAppendPrimitiveIntArray() {
      // int[] primitive 배열 append 병합
      StreamMerger<IntArrayContainer> merger =
          new StreamMerger<>(IntArrayContainer.class);

      IntArrayContainer delta1 = new IntArrayContainer();
      delta1.numbers = new int[]{1, 2};
      merger.applyDelta(delta1);

      IntArrayContainer delta2 = new IntArrayContainer();
      delta2.numbers = new int[]{3, 4};
      merger.applyDelta(delta2);

      IntArrayContainer result = merger.build();
      assertEquals(4, result.numbers.length);
      assertEquals(1, result.numbers[0]);
      assertEquals(2, result.numbers[1]);
      assertEquals(3, result.numbers[2]);
      assertEquals(4, result.numbers[3]);
    }

    @Test
    void shouldMergeObjectArrayByIndex() {
      // @StreamIndex 객체 배열 index 기반 병합
      StreamMerger<ObjectArrayContainer> merger =
          new StreamMerger<>(ObjectArrayContainer.class);

      ObjectArrayContainer delta1 = new ObjectArrayContainer();
      IndexedItem item1 = new IndexedItem();
      item1.index = 0;
      item1.value = "Hello";
      delta1.items = new IndexedItem[]{item1};
      merger.applyDelta(delta1);

      ObjectArrayContainer delta2 = new ObjectArrayContainer();
      IndexedItem item2 = new IndexedItem();
      item2.index = 0;
      item2.value = " world";
      delta2.items = new IndexedItem[]{item2};
      merger.applyDelta(delta2);

      ObjectArrayContainer result = merger.build();
      assertEquals(1, result.items.length);
      assertEquals(0, result.items[0].index);
      assertEquals("Hello world", result.items[0].value);
    }

    @Test
    void shouldUseStreamListWithArray() {
      // @StreamList(index = "seq")로 배열 인덱스 필드 지정
      StreamMerger<StreamListArrayContainer> merger =
          new StreamMerger<>(StreamListArrayContainer.class);

      StreamListArrayContainer delta1 = new StreamListArrayContainer();
      ExternalItem item1 = new ExternalItem();
      item1.seq = 0;
      item1.content = "First";
      delta1.items = new ExternalItem[]{item1};
      merger.applyDelta(delta1);

      StreamListArrayContainer delta2 = new StreamListArrayContainer();
      ExternalItem item2 = new ExternalItem();
      item2.seq = 0;
      item2.content = " Second";
      delta2.items = new ExternalItem[]{item2};
      merger.applyDelta(delta2);

      StreamListArrayContainer result = merger.build();
      assertEquals(1, result.items.length);
      assertEquals(0, result.items[0].seq);
      assertEquals("First Second", result.items[0].content);
    }

    @Test
    void shouldOverwriteArrayWithStreamOverwrite() {
      // @StreamOverwrite 배열 전체 덮어쓰기
      StreamMerger<OverwriteArrayContainer> merger =
          new StreamMerger<>(OverwriteArrayContainer.class);

      OverwriteArrayContainer delta1 = new OverwriteArrayContainer();
      delta1.tags = new String[]{"a", "b"};
      merger.applyDelta(delta1);

      OverwriteArrayContainer delta2 = new OverwriteArrayContainer();
      delta2.tags = new String[]{"c"};
      merger.applyDelta(delta2);

      OverwriteArrayContainer result = merger.build();
      // @StreamOverwrite이므로 ["c"]로 덮어쓰기
      assertEquals(1, result.tags.length);
      assertEquals("c", result.tags[0]);
    }

    @Test
    void shouldAppendNoIndexObjectArray() {
      // index 없는 객체 배열 append 병합
      StreamMerger<NoIndexArrayContainer> merger =
          new StreamMerger<>(NoIndexArrayContainer.class);

      NoIndexArrayContainer delta1 = new NoIndexArrayContainer();
      NoIndexItem item1 = new NoIndexItem();
      item1.value = "first";
      delta1.items = new NoIndexItem[]{item1};
      merger.applyDelta(delta1);

      NoIndexArrayContainer delta2 = new NoIndexArrayContainer();
      NoIndexItem item2 = new NoIndexItem();
      item2.value = "second";
      delta2.items = new NoIndexItem[]{item2};
      merger.applyDelta(delta2);

      NoIndexArrayContainer result = merger.build();
      // index 필드 없음 → append → 2개의 아이템
      assertEquals(2, result.items.length);
      assertEquals("first", result.items[0].value);
      assertEquals("second", result.items[1].value);
    }

    @Test
    void shouldSupportArrayInRecord() {
      // Record + 배열 필드 지원
      StreamMerger<ArrayRecord> merger =
          new StreamMerger<>(ArrayRecord.class);

      IndexedItem item1 = new IndexedItem();
      item1.index = 0;
      item1.value = "Hello";

      ArrayRecord delta1 = new ArrayRecord("id1", new String[]{"tag1"}, new IndexedItem[]{item1});
      merger.applyDelta(delta1);

      IndexedItem item2 = new IndexedItem();
      item2.index = 0;
      item2.value = " world";

      ArrayRecord delta2 = new ArrayRecord(null, new String[]{"tag2"}, new IndexedItem[]{item2});
      merger.applyDelta(delta2);

      ArrayRecord result = merger.build();
      assertEquals("id1", result.id());
      assertEquals(2, result.tags().length);
      assertEquals("tag1", result.tags()[0]);
      assertEquals("tag2", result.tags()[1]);
      assertEquals(1, result.items().length);
      assertEquals("Hello world", result.items()[0].value);
    }
  }

  /**
   * Map 타입 지원 테스트.
   * key 기반 병합이 올바르게 동작하는지 검증한다.
   */
  @Nested
  class MapSupport {

    @Test
    void shouldConcatStringValueMapByKey() {
      // String value Map: 기존 키는 연결(concat)
      StreamMerger<PrimitiveMapContainer> merger =
          new StreamMerger<>(PrimitiveMapContainer.class);

      PrimitiveMapContainer delta1 = new PrimitiveMapContainer();
      delta1.metadata = new HashMap<>();
      delta1.metadata.put("key1", "Hello");
      delta1.metadata.put("key2", "World");
      merger.applyDelta(delta1);

      PrimitiveMapContainer delta2 = new PrimitiveMapContainer();
      delta2.metadata = new HashMap<>();
      delta2.metadata.put("key2", "!");       // 기존 키: 연결
      delta2.metadata.put("key3", "New");     // 새 키: 추가
      merger.applyDelta(delta2);

      PrimitiveMapContainer result = merger.build();
      assertEquals(3, result.metadata.size());
      assertEquals("Hello", result.metadata.get("key1"));   // 유지
      assertEquals("World!", result.metadata.get("key2"));  // 연결됨
      assertEquals("New", result.metadata.get("key3"));     // 추가
    }

    @Test
    void shouldSumIntegerValueMapByKey() {
      // Integer value Map: 기존 키는 합산
      StreamMerger<IntegerMapContainer> merger =
          new StreamMerger<>(IntegerMapContainer.class);

      IntegerMapContainer delta1 = new IntegerMapContainer();
      delta1.counts = new HashMap<>();
      delta1.counts.put("a", 10);
      delta1.counts.put("b", 20);
      merger.applyDelta(delta1);

      IntegerMapContainer delta2 = new IntegerMapContainer();
      delta2.counts = new HashMap<>();
      delta2.counts.put("b", 30);  // 기존 키: 합산
      delta2.counts.put("c", 40);  // 새 키: 추가
      merger.applyDelta(delta2);

      IntegerMapContainer result = merger.build();
      assertEquals(3, result.counts.size());
      assertEquals(10, result.counts.get("a"));  // 유지
      assertEquals(50, result.counts.get("b"));  // 합산됨 (20 + 30)
      assertEquals(40, result.counts.get("c"));  // 추가
    }

    @Test
    void shouldMergeObjectValueMapRecursively() {
      // 객체 value Map의 재귀적 병합
      StreamMerger<ObjectMapContainer> merger =
          new StreamMerger<>(ObjectMapContainer.class);

      ObjectMapContainer delta1 = new ObjectMapContainer();
      delta1.items = new HashMap<>();
      SimpleDto item1 = new SimpleDto();
      item1.content = "Hello";
      item1.count = 10;
      delta1.items.put("item1", item1);
      merger.applyDelta(delta1);

      ObjectMapContainer delta2 = new ObjectMapContainer();
      delta2.items = new HashMap<>();
      SimpleDto item2 = new SimpleDto();
      item2.content = " world";  // 기존 키의 content 연결
      item2.count = 5;           // 기존 키의 count 합산
      delta2.items.put("item1", item2);
      SimpleDto newItem = new SimpleDto();
      newItem.content = "New";
      delta2.items.put("item2", newItem);  // 새 키 추가
      merger.applyDelta(delta2);

      ObjectMapContainer result = merger.build();
      assertEquals(2, result.items.size());
      assertEquals("Hello world", result.items.get("item1").content);
      assertEquals(15, result.items.get("item1").count);
      assertEquals("New", result.items.get("item2").content);
    }

    @Test
    void shouldOverwriteMapWithStreamOverwrite() {
      // @StreamOverwrite Map 전체 덮어쓰기
      StreamMerger<OverwriteMapContainer> merger =
          new StreamMerger<>(OverwriteMapContainer.class);

      OverwriteMapContainer delta1 = new OverwriteMapContainer();
      delta1.config = new HashMap<>();
      delta1.config.put("key1", "value1");
      delta1.config.put("key2", "value2");
      merger.applyDelta(delta1);

      OverwriteMapContainer delta2 = new OverwriteMapContainer();
      delta2.config = new HashMap<>();
      delta2.config.put("key3", "value3");  // 전체 덮어쓰기
      merger.applyDelta(delta2);

      OverwriteMapContainer result = merger.build();
      // @StreamOverwrite이므로 전체 교체됨
      assertEquals(1, result.config.size());
      assertNull(result.config.get("key1"));
      assertNull(result.config.get("key2"));
      assertEquals("value3", result.config.get("key3"));
    }

    @Test
    void shouldMergeNestedObjectValueMap() {
      // 중첩된 객체 value Map의 재귀적 병합
      StreamMerger<NestedObjectMapContainer> merger =
          new StreamMerger<>(NestedObjectMapContainer.class);

      NestedObjectMapContainer delta1 = new NestedObjectMapContainer();
      delta1.items = new HashMap<>();
      NestedMapItem item1 = new NestedMapItem();
      item1.name = "Item";
      item1.nested = new SimpleDto();
      item1.nested.content = "Hello";
      delta1.items.put("key1", item1);
      merger.applyDelta(delta1);

      NestedObjectMapContainer delta2 = new NestedObjectMapContainer();
      delta2.items = new HashMap<>();
      NestedMapItem item2 = new NestedMapItem();
      item2.name = " One";  // String 연결
      item2.nested = new SimpleDto();
      item2.nested.content = " world";  // nested의 content도 연결
      delta2.items.put("key1", item2);
      merger.applyDelta(delta2);

      NestedObjectMapContainer result = merger.build();
      assertEquals(1, result.items.size());
      assertEquals("Item One", result.items.get("key1").name);
      assertEquals("Hello world", result.items.get("key1").nested.content);
    }

    @Test
    void shouldHandleEmptyMap() {
      // 빈 Map 처리
      StreamMerger<PrimitiveMapContainer> merger =
          new StreamMerger<>(PrimitiveMapContainer.class);

      PrimitiveMapContainer delta1 = new PrimitiveMapContainer();
      delta1.metadata = new HashMap<>();
      delta1.metadata.put("key1", "value1");
      merger.applyDelta(delta1);

      PrimitiveMapContainer delta2 = new PrimitiveMapContainer();
      delta2.metadata = new HashMap<>();  // 빈 Map
      merger.applyDelta(delta2);

      PrimitiveMapContainer result = merger.build();
      assertEquals(1, result.metadata.size());
      assertEquals("value1", result.metadata.get("key1"));  // 유지됨
    }
  }

  /**
   * TypeVariable 해석 테스트.
   * 제네릭 상속 구조에서 TypeVariable이 올바르게 해석되는지 검증한다.
   */
  @Nested
  class TypeVariableResolution {

    @Test
    void shouldResolveTypeVariableInInheritance() {
      // CitedResponse extends BaseResponse<CitedMessage>
      // choices 필드는 List<GenericChoice<CitedMessage>>가 됨
      StreamMerger<CitedResponse> merger = new StreamMerger<>(CitedResponse.class);

      // 첫 번째 델타
      CitedResponse delta1 = new CitedResponse();
      delta1.id = "resp_1";
      delta1.choices = new ArrayList<>();

      GenericChoice<CitedMessage> choice1 = new GenericChoice<>();
      choice1.index = 0;
      choice1.message = new CitedMessage();
      choice1.message.content = "Hello";
      choice1.message.citation = "source1";
      delta1.choices.add(choice1);

      merger.applyDelta(delta1);

      // 두 번째 델타 - 같은 index의 choice에 추가 내용
      CitedResponse delta2 = new CitedResponse();
      delta2.choices = new ArrayList<>();

      GenericChoice<CitedMessage> choice2 = new GenericChoice<>();
      choice2.index = 0;
      choice2.message = new CitedMessage();
      choice2.message.content = " world";
      choice2.message.citation = ", source2";
      delta2.choices.add(choice2);

      merger.applyDelta(delta2);

      // 검증
      CitedResponse result = merger.build();
      assertEquals("resp_1", result.id);
      assertEquals(1, result.choices.size());

      GenericChoice<CitedMessage> resultChoice = result.choices.get(0);
      assertEquals(0, resultChoice.index);
      assertNotNull(resultChoice.message);
      assertEquals("Hello world", resultChoice.message.content);
      assertEquals("source1, source2", resultChoice.message.citation);
    }

    @Test
    void shouldHandleMultipleLevelInheritance() {
      // ExtendedCitedResponse extends CitedResponse extends BaseResponse<CitedMessage>
      StreamMerger<ExtendedCitedResponse> merger =
          new StreamMerger<>(ExtendedCitedResponse.class);

      ExtendedCitedResponse delta1 = new ExtendedCitedResponse();
      delta1.id = "ext_1";
      delta1.metadata = "meta";
      delta1.choices = new ArrayList<>();

      GenericChoice<CitedMessage> choice1 = new GenericChoice<>();
      choice1.index = 0;
      choice1.message = new CitedMessage();
      choice1.message.content = "Test";
      delta1.choices.add(choice1);

      merger.applyDelta(delta1);

      ExtendedCitedResponse result = merger.build();
      assertEquals("ext_1", result.id);
      assertEquals("meta", result.metadata);
      assertEquals(1, result.choices.size());
      assertEquals("Test", result.choices.get(0).message.content);
    }

    @Test
    void shouldHandleMultipleTypeParameters() {
      // MultiTypeResponse extends BaseMultiResponse<KeyItem, ValueItem>
      StreamMerger<MultiTypeResponse> merger =
          new StreamMerger<>(MultiTypeResponse.class);

      MultiTypeResponse delta1 = new MultiTypeResponse();
      delta1.keys = new ArrayList<>();
      delta1.values = new ArrayList<>();

      KeyItem key1 = new KeyItem();
      key1.index = 0;
      key1.keyName = "key";
      delta1.keys.add(key1);

      ValueItem val1 = new ValueItem();
      val1.index = 0;
      val1.data = "data";
      delta1.values.add(val1);

      merger.applyDelta(delta1);

      // 두 번째 델타
      MultiTypeResponse delta2 = new MultiTypeResponse();
      delta2.keys = new ArrayList<>();
      delta2.values = new ArrayList<>();

      KeyItem key2 = new KeyItem();
      key2.index = 0;
      key2.keyName = "Name";  // String concatenation
      delta2.keys.add(key2);

      ValueItem val2 = new ValueItem();
      val2.index = 0;
      val2.data = "Value";  // String concatenation
      delta2.values.add(val2);

      merger.applyDelta(delta2);

      MultiTypeResponse result = merger.build();
      assertEquals(1, result.keys.size());
      assertEquals(1, result.values.size());
      assertEquals("keyName", result.keys.get(0).keyName);
      assertEquals("dataValue", result.values.get(0).data);
    }
  }

  // Test DTOs

  @Getter @Setter
  public static class SimpleDto {
    String content;
    Integer count;
    Long timestamp;
  }

  /**
   * primitive 타입 필드 테스트용 DTO.
   * int, double, boolean 등 primitive 타입 사용.
   */
  @Getter @Setter
  public static class PrimitiveDto {
    int age;
    double score;
    boolean active;
  }

  @Getter @Setter
  public static class IndexedItem {
    @StreamIndex
    Integer index;
    String value;
  }

  @Getter @Setter
  public static class TypedItem {
    @StreamOverwrite
    String type;
    String name;
  }

  @Getter @Setter
  public static class Parent {
    String id;
    SimpleDto child;
  }

  @Getter @Setter
  public static class GrandParent {
    String name;
    Parent parent;
  }

  @Getter @Setter
  public static class ListContainer {
    List<String> tags;
    List<IndexedItem> items;
  }

  public record SimpleRecord(String content, Integer count) {}

  public record ParentRecord(String id, SimpleRecord child) {}

  // Custom merge method test class
  @Getter @Setter
  public static class CustomChunk {
    private String content;

    public CustomChunk() {
    }

    public CustomChunk(String content) {
      this.content = content;
    }

    public CustomChunk merge(CustomChunk delta) {
      String newContent = (this.content == null ? "" : this.content)
          + (delta.content == null ? "" : delta.content);
      return new CustomChunk(newContent);
    }
  }

  /**
   * 중첩된 customMerge 테스트용 컨테이너.
   */
  @Getter @Setter
  public static class NestedCustomMergeContainer {
    String name;
    CustomChunk chunk;
  }

  /**
   * index가 있는 customMerge 객체.
   */
  @Getter @Setter
  public static class IndexedCustomChunk {
    @StreamIndex
    private int index;
    private String content;

    public IndexedCustomChunk() {
    }

    public IndexedCustomChunk(int index, String content) {
      this.index = index;
      this.content = content;
    }

    public IndexedCustomChunk merge(IndexedCustomChunk delta) {
      String newContent = (this.content == null ? "" : this.content)
          + (delta.content == null ? "" : delta.content);
      return new IndexedCustomChunk(this.index, newContent);
    }
  }

  /**
   * customMerge 객체 List 컨테이너.
   */
  @Getter @Setter
  public static class CustomMergeListContainer {
    List<IndexedCustomChunk> items;
  }

  // OpenAI-like DTOs

  @Getter @Setter
  public static class ChatCompletionChunk {
    String id;
    List<Choice> choices;
  }

  @Getter @Setter
  public static class Choice {
    @StreamIndex
    Integer index;
    String finishReason;
    Delta delta;
  }

  @Getter @Setter
  public static class Delta {
    String role;
    String content;
    List<ToolCall> toolCalls;
  }

  @Getter @Setter
  public static class ToolCall {
    @StreamIndex
    Integer index;
    String id;
    String type;
    FunctionCall function;
  }

  @Getter @Setter
  public static class FunctionCall {
    String name;
    String arguments;
  }

  // Bad DTOs for exception testing

  @Getter @Setter
  public static class BadListContainer {
    List<NoIndexItem> items;
  }

  @Getter @Setter
  public static class NoIndexItem {
    String value;
  }

  @Getter @Setter
  public static class NumberDto {
    Double price;
  }

  @Getter @Setter
  public static class ConventionIndexItem {
    Integer index;  // @StreamIndex 없이 "index" 이름만으로 자동 인식
    String data;
  }

  // === TypeVariable Resolution Test DTOs ===

  /**
   * 제네릭 베이스 응답 클래스.
   * T는 메시지 타입을 나타낸다.
   */
  @Getter @Setter
  public static class BaseResponse<T> {
    String id;
    List<GenericChoice<T>> choices;
  }

  /**
   * 제네릭 Choice 클래스.
   * T는 메시지 타입을 나타낸다.
   */
  @Getter @Setter
  public static class GenericChoice<T> {
    @StreamIndex
    Integer index;
    T message;
  }

  /**
   * 인용 정보가 포함된 메시지.
   */
  @Getter @Setter
  public static class CitedMessage {
    String content;
    String citation;
  }

  /**
   * CitedMessage를 사용하는 구체적인 응답 클래스.
   * BaseResponse<CitedMessage>를 상속.
   */
  @Getter @Setter
  public static class CitedResponse extends BaseResponse<CitedMessage> {
    // choices는 List<GenericChoice<CitedMessage>>가 됨
  }

  /**
   * 다중 레벨 상속 테스트용 클래스.
   */
  @Getter @Setter
  public static class ExtendedCitedResponse extends CitedResponse {
    String metadata;
  }

  /**
   * 다중 타입 파라미터를 가진 베이스 클래스.
   */
  @Getter @Setter
  public static class BaseMultiResponse<K, V> {
    List<K> keys;
    List<V> values;
  }

  /**
   * 키 아이템.
   */
  @Getter @Setter
  public static class KeyItem {
    @StreamIndex
    Integer index;
    String keyName;
  }

  /**
   * 값 아이템.
   */
  @Getter @Setter
  public static class ValueItem {
    @StreamIndex
    Integer index;
    String data;
  }

  /**
   * 다중 타입 파라미터를 사용하는 구체적인 응답 클래스.
   */
  @Getter @Setter
  public static class MultiTypeResponse extends BaseMultiResponse<KeyItem, ValueItem> {
    // keys는 List<KeyItem>, values는 List<ValueItem>이 됨
  }

  // === StreamIndex Type Support Test DTOs ===

  /**
   * primitive int 인덱스를 가진 아이템.
   */
  @Getter @Setter
  public static class PrimitiveIntIndexItem {
    @StreamIndex
    int idx;
    String value;
  }

  @Getter @Setter
  public static class ListWithPrimitiveIndex {
    List<PrimitiveIntIndexItem> items;
  }


  /**
   * String 인덱스를 가진 아이템 (지원되지 않는 타입, append로 동작).
   */
  @Getter @Setter
  public static class StringIndexItem {
    @StreamIndex
    String idx;
    String value;
  }

  @Getter @Setter
  public static class ListWithStringIndex {
    List<StringIndexItem> items;
  }

  /**
   * "index"라는 이름의 String 필드를 가진 아이템.
   * int/Integer가 아니므로 index 필드로 인식되지 않음 → append로 동작.
   */
  @Getter @Setter
  public static class StringNamedIndexItem {
    String index;  // "index" 이름이지만 String 타입
    String value;
  }

  @Getter @Setter
  public static class ListWithStringNamedIndex {
    List<StringNamedIndexItem> items;
  }

  // === @StreamList Test DTOs ===

  /**
   * @StreamList 없이 index 필드도 없는 아이템.
   * 이 아이템은 @StreamList로 외부에서 인덱스 필드를 지정해야 한다.
   */
  @Getter @Setter
  public static class ExternalItem {
    int seq;  // @StreamIndex 없음, "index" 이름도 아님
    String content;
  }

  /**
   * @StreamList(index = "seq")로 인덱스 필드를 지정하는 컨테이너.
   */
  @Getter @Setter
  public static class StreamListContainer {
    @StreamList(index = "seq")
    List<ExternalItem> items;
  }

  /**
   * @StreamList(index = "seq")로 인덱스 필드를 지정하는 컨테이너.
   * index 속성 사용 테스트.
   */
  @Getter @Setter
  public static class StreamListIndexAttrContainer {
    @StreamList(index = "seq")
    List<ExternalItem> items;
  }

  /**
   * @StreamList로 존재하지 않는 필드를 지정한 경우.
   * append 동작으로 폴백.
   */
  @Getter @Setter
  public static class StreamListInvalidFieldContainer {
    @StreamList(index = "nonExistent")
    List<ExternalItem> items;
  }

  /**
   * @StreamList로 String 타입 필드를 지정한 경우.
   * int/Integer가 아니므로 무시되고 append 동작.
   */
  @Getter @Setter
  public static class StreamListStringFieldItem {
    String seq;  // String 타입
    String content;
  }

  @Getter @Setter
  public static class StreamListStringFieldContainer {
    @StreamList(index = "seq")
    List<StreamListStringFieldItem> items;
  }

  /**
   * @StreamList가 요소 클래스의 @StreamIndex보다 우선하는지 테스트.
   */
  @Getter @Setter
  public static class ItemWithStreamIndex {
    @StreamIndex
    Integer index;  // 요소 클래스에 정의된 인덱스
    int customIdx;  // @StreamList로 지정할 다른 인덱스
    String value;
  }

  @Getter @Setter
  public static class StreamListOverrideContainer {
    @StreamList(index = "customIdx")  // @StreamIndex보다 우선
    List<ItemWithStreamIndex> items;
  }

  // === @StreamOverwrite List Test DTOs ===

  /**
   * @StreamOverwrite가 기본 타입 List 필드에 적용된 경우.
   * 병합(append) 대신 전체 덮어쓰기됨.
   */
  @Getter @Setter
  public static class OverwriteListContainer {
    @StreamOverwrite
    List<String> tags;
  }

  /**
   * @StreamOverwrite가 객체 List 필드에 적용된 경우.
   * index 기반 병합 대신 전체 덮어쓰기됨.
   */
  @Getter @Setter
  public static class OverwriteObjectListContainer {
    @StreamOverwrite
    List<IndexedItem> items;
  }

  // === Array Test DTOs ===

  /**
   * String 배열을 가진 컨테이너.
   */
  @Getter @Setter
  public static class StringArrayContainer {
    String[] tags;
  }

  /**
   * primitive int 배열을 가진 컨테이너.
   */
  @Getter @Setter
  public static class IntArrayContainer {
    int[] numbers;
  }

  /**
   * @StreamIndex를 가진 객체 배열 컨테이너.
   */
  @Getter @Setter
  public static class ObjectArrayContainer {
    IndexedItem[] items;
  }

  /**
   * @StreamList로 인덱스 필드를 지정한 배열 컨테이너.
   */
  @Getter @Setter
  public static class StreamListArrayContainer {
    @StreamList(index = "seq")
    ExternalItem[] items;
  }

  /**
   * @StreamOverwrite 배열 컨테이너.
   */
  @Getter @Setter
  public static class OverwriteArrayContainer {
    @StreamOverwrite
    String[] tags;
  }

  /**
   * index 필드가 없는 객체 배열 컨테이너.
   */
  @Getter @Setter
  public static class NoIndexArrayContainer {
    NoIndexItem[] items;
  }

  /**
   * Record에서 배열 필드 테스트.
   */
  public record ArrayRecord(String id, String[] tags, IndexedItem[] items) {}

  // === Map Test DTOs ===

  /**
   * primitive value Map을 가진 컨테이너.
   */
  @Getter @Setter
  public static class PrimitiveMapContainer {
    Map<String, String> metadata;
  }

  /**
   * Integer value Map을 가진 컨테이너.
   */
  @Getter @Setter
  public static class IntegerMapContainer {
    Map<String, Integer> counts;
  }

  /**
   * 객체 value Map을 가진 컨테이너.
   */
  @Getter @Setter
  public static class ObjectMapContainer {
    Map<String, SimpleDto> items;
  }

  /**
   * @StreamOverwrite Map 컨테이너.
   */
  @Getter @Setter
  public static class OverwriteMapContainer {
    @StreamOverwrite
    Map<String, String> config;
  }

  /**
   * 중첩된 객체를 가진 Map value 아이템.
   */
  @Getter @Setter
  public static class NestedMapItem {
    String name;
    SimpleDto nested;
  }

  /**
   * 중첩된 객체 value Map 컨테이너.
   */
  @Getter @Setter
  public static class NestedObjectMapContainer {
    Map<String, NestedMapItem> items;
  }

  // === Interface Field Support Test DTOs ===

  /**
   * 문서 인터페이스 (인터페이스 필드 테스트용).
   */
  public interface IDocument {
    String getTitle();
    String getContent();
  }

  /**
   * IDocument의 첫 번째 구현체.
   */
  @Getter @Setter
  public static class SimpleDocument implements IDocument {
    private String title;
    private String content;

    public SimpleDocument() {}

    public SimpleDocument(String title, String content) {
      this.title = title;
      this.content = content;
    }
  }

  /**
   * IDocument의 두 번째 구현체 (다형성 테스트용).
   */
  @Getter @Setter
  public static class RichDocument implements IDocument {
    private String title;
    private String content;
    private String author;

    public RichDocument() {}

    public RichDocument(String title, String content, String author) {
      this.title = title;
      this.content = content;
      this.author = author;
    }
  }

  /**
   * 인터페이스 필드를 가진 컨테이너.
   */
  @Getter @Setter
  public static class InterfaceFieldContainer {
    private String name;
    private IDocument document;
  }

  /**
   * 인터페이스 List 필드를 가진 컨테이너.
   */
  @Getter @Setter
  public static class InterfaceListContainer {
    private String name;
    private List<IDocument> documents;
  }

  /**
   * @StreamIndex가 있는 인덱스 문서 (인터페이스 List 병합 테스트용).
   */
  public interface IIndexedDocument {
    int getIndex();
    String getContent();
  }

  @Getter @Setter
  public static class IndexedDocument implements IIndexedDocument {
    @StreamIndex
    private int index;
    private String content;

    public IndexedDocument() {}

    public IndexedDocument(int index, String content) {
      this.index = index;
      this.content = content;
    }
  }

  /**
   * 인덱스가 있는 인터페이스 List 컨테이너.
   */
  @Getter @Setter
  public static class IndexedInterfaceListContainer {
    private List<IIndexedDocument> documents;
  }

  /**
   * 인터페이스 필드 지원 테스트.
   * 인터페이스/추상 클래스 필드가 올바르게 병합되고 복원되는지 검증한다.
   */
  @Nested
  class InterfaceFieldSupport {

    @Test
    void shouldMergeInterfaceField() {
      // 인터페이스 필드가 있는 객체 병합
      StreamMerger<InterfaceFieldContainer> merger =
          new StreamMerger<>(InterfaceFieldContainer.class);

      InterfaceFieldContainer delta1 = new InterfaceFieldContainer();
      delta1.name = "Container";
      delta1.document = new SimpleDocument("Hello", " doc");
      merger.applyDelta(delta1);

      InterfaceFieldContainer delta2 = new InterfaceFieldContainer();
      delta2.name = " One";
      delta2.document = new SimpleDocument(" World", "ument");
      merger.applyDelta(delta2);

      InterfaceFieldContainer result = merger.build();
      assertEquals("Container One", result.name);
      assertNotNull(result.document);
      assertInstanceOf(SimpleDocument.class, result.document);
      assertEquals("Hello World", result.document.getTitle());
      assertEquals(" document", result.document.getContent());
    }

    @Test
    void shouldMergeInterfaceListField() {
      // 인터페이스 List 필드가 있는 객체 병합 (인덱스 없이 append)
      StreamMerger<InterfaceListContainer> merger =
          new StreamMerger<>(InterfaceListContainer.class);

      InterfaceListContainer delta1 = new InterfaceListContainer();
      delta1.name = "List";
      delta1.documents = new ArrayList<>();
      delta1.documents.add(new SimpleDocument("Doc1", "Content1"));
      merger.applyDelta(delta1);

      InterfaceListContainer delta2 = new InterfaceListContainer();
      delta2.name = " Container";
      delta2.documents = new ArrayList<>();
      delta2.documents.add(new RichDocument("Doc2", "Content2", "Author"));
      merger.applyDelta(delta2);

      InterfaceListContainer result = merger.build();
      assertEquals("List Container", result.name);
      assertEquals(2, result.documents.size());

      // 첫 번째는 SimpleDocument
      assertInstanceOf(SimpleDocument.class, result.documents.get(0));
      assertEquals("Doc1", result.documents.get(0).getTitle());

      // 두 번째는 RichDocument
      assertInstanceOf(RichDocument.class, result.documents.get(1));
      assertEquals("Doc2", result.documents.get(1).getTitle());
      assertEquals("Author", ((RichDocument) result.documents.get(1)).getAuthor());
    }

    @Test
    void shouldMergeIndexedInterfaceListByIndex() {
      // @StreamIndex가 있는 인터페이스 List 병합
      StreamMerger<IndexedInterfaceListContainer> merger =
          new StreamMerger<>(IndexedInterfaceListContainer.class);

      IndexedInterfaceListContainer delta1 = new IndexedInterfaceListContainer();
      delta1.documents = new ArrayList<>();
      delta1.documents.add(new IndexedDocument(0, "Hello"));
      delta1.documents.add(new IndexedDocument(1, "World"));
      merger.applyDelta(delta1);

      IndexedInterfaceListContainer delta2 = new IndexedInterfaceListContainer();
      delta2.documents = new ArrayList<>();
      delta2.documents.add(new IndexedDocument(0, " Friend"));  // 같은 index → 병합
      merger.applyDelta(delta2);

      IndexedInterfaceListContainer result = merger.build();
      assertEquals(2, result.documents.size());

      assertInstanceOf(IndexedDocument.class, result.documents.get(0));
      assertEquals(0, result.documents.get(0).getIndex());
      assertEquals("Hello Friend", result.documents.get(0).getContent());

      assertInstanceOf(IndexedDocument.class, result.documents.get(1));
      assertEquals(1, result.documents.get(1).getIndex());
      assertEquals("World", result.documents.get(1).getContent());
    }

    @Test
    void shouldPreservePolymorphicTypesInList() {
      // 다형성: 같은 List에 여러 구현체가 섞여 있는 경우
      StreamMerger<InterfaceListContainer> merger =
          new StreamMerger<>(InterfaceListContainer.class);

      InterfaceListContainer delta1 = new InterfaceListContainer();
      delta1.documents = new ArrayList<>();
      delta1.documents.add(new SimpleDocument("Simple", "Content"));
      delta1.documents.add(new RichDocument("Rich", "Content", "Alice"));
      delta1.documents.add(new SimpleDocument("Simple2", "Content2"));
      merger.applyDelta(delta1);

      InterfaceListContainer result = merger.build();
      assertEquals(3, result.documents.size());

      // 각 요소가 원래 타입을 유지하는지 확인
      assertInstanceOf(SimpleDocument.class, result.documents.get(0));
      assertInstanceOf(RichDocument.class, result.documents.get(1));
      assertInstanceOf(SimpleDocument.class, result.documents.get(2));

      // RichDocument의 추가 필드도 보존되는지 확인
      RichDocument rich = (RichDocument) result.documents.get(1);
      assertEquals("Rich", rich.getTitle());
      assertEquals("Alice", rich.getAuthor());
    }

    @Test
    void shouldMergeInterfaceValueMap() {
      // 인터페이스 value Map 병합
      StreamMerger<InterfaceMapContainer> merger =
          new StreamMerger<>(InterfaceMapContainer.class);

      InterfaceMapContainer delta1 = new InterfaceMapContainer();
      delta1.documents = new HashMap<>();
      delta1.documents.put("simple", new SimpleDocument("Title1", "Content1"));
      delta1.documents.put("rich", new RichDocument("Title2", "Content2", "Bob"));
      merger.applyDelta(delta1);

      InterfaceMapContainer delta2 = new InterfaceMapContainer();
      delta2.documents = new HashMap<>();
      delta2.documents.put("simple", new SimpleDocument(" Updated", " More"));  // 기존 키 병합
      delta2.documents.put("new", new SimpleDocument("New", "Doc"));  // 새 키
      merger.applyDelta(delta2);

      InterfaceMapContainer result = merger.build();
      assertEquals(3, result.documents.size());

      // simple 키: 병합됨
      assertInstanceOf(SimpleDocument.class, result.documents.get("simple"));
      assertEquals("Title1 Updated", result.documents.get("simple").getTitle());
      assertEquals("Content1 More", result.documents.get("simple").getContent());

      // rich 키: 유지됨
      assertInstanceOf(RichDocument.class, result.documents.get("rich"));
      assertEquals("Title2", result.documents.get("rich").getTitle());
      assertEquals("Bob", ((RichDocument) result.documents.get("rich")).getAuthor());

      // new 키: 추가됨
      assertInstanceOf(SimpleDocument.class, result.documents.get("new"));
      assertEquals("New", result.documents.get("new").getTitle());
    }

    @Test
    void shouldPreservePolymorphicTypesInMap() {
      // Map에서 다형성 타입 보존
      StreamMerger<InterfaceMapContainer> merger =
          new StreamMerger<>(InterfaceMapContainer.class);

      InterfaceMapContainer delta1 = new InterfaceMapContainer();
      delta1.documents = new HashMap<>();
      delta1.documents.put("a", new SimpleDocument("Simple", "Doc"));
      delta1.documents.put("b", new RichDocument("Rich", "Doc", "Author"));
      merger.applyDelta(delta1);

      InterfaceMapContainer result = merger.build();

      // 각 value가 원래 타입을 유지하는지 확인
      assertInstanceOf(SimpleDocument.class, result.documents.get("a"));
      assertInstanceOf(RichDocument.class, result.documents.get("b"));
      assertEquals("Author", ((RichDocument) result.documents.get("b")).getAuthor());
    }
  }

  /**
   * 인터페이스 value Map 컨테이너.
   */
  @Getter @Setter
  public static class InterfaceMapContainer {
    private Map<String, IDocument> documents;
  }
}
