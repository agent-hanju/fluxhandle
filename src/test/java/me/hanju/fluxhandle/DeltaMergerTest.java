package me.hanju.fluxhandle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import lombok.Getter;
import lombok.Setter;
import me.hanju.fluxhandle.deltastream.annotation.StreamIndex;
import me.hanju.fluxhandle.deltastream.annotation.StreamOverwrite;
import me.hanju.fluxhandle.deltastream.merge.DeltaMerger;
import me.hanju.fluxhandle.deltastream.merge.MergeException;
import me.hanju.fluxhandle.deltastream.metadata.TypeMetadataCache;

class DeltaMergerTest {

  @BeforeEach
  void setUp() {
    TypeMetadataCache.clearCache();
  }

  @Nested
  class BasicMergeRules {

    @Test
    void shouldAppendStringFields() {
      DeltaMerger<SimpleDto> merger = new DeltaMerger<>(SimpleDto.class);

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
      DeltaMerger<SimpleDto> merger = new DeltaMerger<>(SimpleDto.class);

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
      DeltaMerger<SimpleDto> merger = new DeltaMerger<>(SimpleDto.class);

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
      DeltaMerger<NumberDto> merger = new DeltaMerger<>(NumberDto.class);

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
      DeltaMerger<ConventionIndexItem> merger = new DeltaMerger<>(ConventionIndexItem.class);

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
      DeltaMerger<SimpleDto> merger = new DeltaMerger<>(SimpleDto.class);

      SimpleDto result = merger.build();
      assertNotNull(result);
      assertNull(result.content);
      assertNull(result.count);
    }

    @Test
    void shouldReplaceSpecialKeyIndex() {
      DeltaMerger<IndexedItem> merger = new DeltaMerger<>(IndexedItem.class);

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
      DeltaMerger<TypedItem> merger = new DeltaMerger<>(TypedItem.class);

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
    void shouldSkipNullDeltaValues() {
      DeltaMerger<SimpleDto> merger = new DeltaMerger<>(SimpleDto.class);

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
      DeltaMerger<SimpleDto> merger = new DeltaMerger<>(SimpleDto.class);

      SimpleDto delta1 = new SimpleDto();
      delta1.content = "Hello";
      merger.applyDelta(delta1);

      merger.applyDelta(null);

      SimpleDto result = merger.build();
      assertEquals("Hello", result.content);
    }
  }

  @Nested
  class NestedObjectMerge {

    @Test
    void shouldMergeNestedObjects() {
      DeltaMerger<Parent> merger = new DeltaMerger<>(Parent.class);

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
      DeltaMerger<GrandParent> merger = new DeltaMerger<>(GrandParent.class);

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
      DeltaMerger<ListContainer> merger = new DeltaMerger<>(ListContainer.class);

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
      DeltaMerger<ListContainer> merger = new DeltaMerger<>(ListContainer.class);

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
      DeltaMerger<ListContainer> merger = new DeltaMerger<>(ListContainer.class);

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
      DeltaMerger<ListContainer> merger = new DeltaMerger<>(ListContainer.class);

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
  }

  @Nested
  class RecordSupport {

    @Test
    void shouldBuildRecordType() {
      DeltaMerger<SimpleRecord> merger = new DeltaMerger<>(SimpleRecord.class);

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
      DeltaMerger<ParentRecord> merger = new DeltaMerger<>(ParentRecord.class);

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
      DeltaMerger<CustomChunk> merger = new DeltaMerger<>(CustomChunk.class);

      merger.applyDelta(new CustomChunk("Hello"));
      merger.applyDelta(new CustomChunk(" "));
      merger.applyDelta(new CustomChunk("world"));

      CustomChunk result = merger.build();
      assertEquals("Hello world", result.getContent());
    }

    @Test
    void shouldHandleSingleDeltaWithCustomMerge() {
      DeltaMerger<CustomChunk> merger = new DeltaMerger<>(CustomChunk.class);

      merger.applyDelta(new CustomChunk("only one"));

      CustomChunk result = merger.build();
      assertEquals("only one", result.getContent());
    }
  }

  @Nested
  class OpenAIScenario {

    @Test
    void shouldAssembleTextResponse() {
      DeltaMerger<ChatCompletionChunk> merger =
          new DeltaMerger<>(ChatCompletionChunk.class);

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
      DeltaMerger<ChatCompletionChunk> merger =
          new DeltaMerger<>(ChatCompletionChunk.class);

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
      DeltaMerger<ChatCompletionChunk> merger =
          new DeltaMerger<>(ChatCompletionChunk.class);

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
  class ExceptionCases {

    @Test
    void shouldThrowOnNullType() {
      assertThrows(IllegalArgumentException.class, () ->
          new DeltaMerger<>(null));
    }

    @Test
    void shouldThrowOnMissingStreamIndex() {
      DeltaMerger<BadListContainer> merger =
          new DeltaMerger<>(BadListContainer.class);

      BadListContainer delta = new BadListContainer();
      delta.items = new ArrayList<>();
      NoIndexItem item = new NoIndexItem();
      item.value = "test";
      delta.items.add(item);

      assertThrows(MergeException.class, () ->
          merger.applyDelta(delta));
    }
  }

  // Test DTOs

  @Getter @Setter
  public static class SimpleDto {
    String content;
    Integer count;
    Long timestamp;
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
}
