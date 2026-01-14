package me.hanju.fluxhandle.deltastream.merge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import me.hanju.fluxhandle.deltastream.metadata.FieldMetadata;
import me.hanju.fluxhandle.deltastream.metadata.TypeMetadataCache;
import me.hanju.fluxhandle.deltastream.metadata.TypeMetadataCache.TypeInfo;

/**
 * 델타 스트리밍 객체를 하나의 누적된 결과로 병합하는 클래스
 *
 * <h2>기본 사용법</h2>
 *
 * <pre>{@code
 * DeltaMerger<ChatCompletionChunk> merger = new DeltaMerger<>(ChatCompletionChunk.class);
 *
 * for (ChatCompletionChunk delta : stream) {
 *   merger.applyDelta(delta);
 * }
 *
 * ChatCompletionChunk result = merger.build();
 * }</pre>
 *
 * <h2>병합 규칙</h2>
 * <ul>
 * <li>원시값: 대체</li>
 * <li>{@code String}: 연결 (concatenation)</li>
 * <li>{@code Number}: 합산 (addition)</li>
 * <li>객체: 재귀적 병합({@code T merge(T)} 지원)</li>
 * <li>{@code List/Array}: index 기반 병합 또는 append</li>
 * <li>{@code Map}: key 기반 병합</li>
 * </ul>
 *
 * <p>
 * 자세한 내용은 {@code deltastream} 패키지의 README.md를 참조하세요.
 *
 * @param <T> 병합할 델타 객체의 타입
 * @see me.hanju.fluxhandle.deltastream.annotation.StreamIndex
 * @see me.hanju.fluxhandle.deltastream.annotation.StreamList
 * @see me.hanju.fluxhandle.deltastream.annotation.StreamOverwrite
 */
public final class DeltaMerger<T> {

  private final Class<T> type;
  private final TypeInfo typeInfo;
  private final Map<String, Object> mergingMap;
  private T merged;

  /**
   * 주어진 타입에 대한 DeltaMerger를 생성한다.
   *
   * @param type 병합할 객체의 클래스
   * @throws IllegalArgumentException type이 null인 경우
   */
  public DeltaMerger(final Class<T> type) {
    if (type == null) {
      throw new IllegalArgumentException("type cannot be null");
    }
    this.type = type;
    this.typeInfo = TypeMetadataCache.getTypeInfo(type);
    this.mergingMap = new HashMap<>();
    this.merged = null;
  }

  /**
   * 델타를 누적된 상태에 적용한다.
   *
   * @param delta 병합할 델타 객체
   */
  public void applyDelta(final T delta) {
    if (delta == null) {
      return;
    }
    if (this.typeInfo.hasCustomMerge()) {
      this.applyCustomMerge(delta);
    } else {
      this.mergeIntoMap(this.mergingMap, delta, this.typeInfo);
    }
  }

  /**
   * 최종 병합된 결과를 생성하여 반환한다.
   *
   * @return 병합된 결과 객체
   */
  public T build() {
    if (this.typeInfo.hasCustomMerge()) {
      return this.merged;
    }
    return ObjectBuilder.build(this.type, this.mergingMap);
  }

  // ========== 커스텀 merge 처리 ==========

  @SuppressWarnings("unchecked")
  private void applyCustomMerge(final T delta) {
    if (this.merged == null) {
      this.merged = delta;
    } else {
      try {
        this.merged = (T) this.typeInfo.merge(this.merged, delta);
      } catch (final Exception e) {
        throw new MergeException("merge method invocation failed", e);
      }
    }
  }

  // ========== 핵심 병합 로직 ==========

  private void mergeIntoMap(
      final Map<String, Object> acc,
      final Object delta,
      final TypeInfo info) {

    for (final FieldMetadata field : info.fields()) {
      final Object deltaValue = field.getValue(delta);
      if (deltaValue != null) {
        this.mergeField(acc, field, deltaValue);
      }
    }
  }

  private void mergeField(
      final Map<String, Object> acc,
      final FieldMetadata field,
      final Object deltaValue) {

    final String key = field.fieldName();
    final Object accValue = acc.get(key);

    if (accValue == null) {
      acc.put(key, this.cloneValue(deltaValue, field));
      return;
    }

    if (field.isSpecialKey() || field.isPrimitiveType()) {
      acc.put(key, deltaValue);
      return;
    }

    acc.put(key, this.computeMergedValue(field, accValue, deltaValue));
  }

  @SuppressWarnings("unchecked")
  private Object computeMergedValue(
      final FieldMetadata field,
      final Object accValue,
      final Object deltaValue) {

    if (field.isString() && deltaValue instanceof String deltaStr) {
      return (String) accValue + deltaStr;
    }

    if (field.isNumber() && deltaValue instanceof Number deltaNum) {
      return sumNumbers((Number) accValue, deltaNum);
    }

    if (field.isObject()) {
      return this.mergeNestedObject(field, accValue, deltaValue);
    }

    if (field.isList() && accValue instanceof List && deltaValue instanceof List) {
      this.mergeCollection((List<Object>) accValue, (List<?>) deltaValue, field);
      return accValue;
    }

    if (field.isArray() && accValue instanceof List && deltaValue.getClass().isArray()) {
      this.mergeCollection((List<Object>) accValue, arrayToList(deltaValue), field);
      return accValue;
    }

    if (field.isMap() && accValue instanceof Map && deltaValue instanceof Map) {
      this.mergeMapField((Map<String, Object>) accValue, (Map<String, Object>) deltaValue, field);
      return accValue;
    }

    return deltaValue;
  }

  // ========== 중첩 객체 병합 ==========

  @SuppressWarnings("unchecked")
  private Object mergeNestedObject(
      final FieldMetadata field,
      final Object accValue,
      final Object deltaValue) {

    final TypeInfo nestedInfo = field.getFieldTypeInfo();

    if (nestedInfo.hasCustomMerge() && !(accValue instanceof Map)) {
      try {
        return nestedInfo.merge(accValue, deltaValue);
      } catch (final Exception e) {
        throw new MergeException("nested merge method invocation failed", e);
      }
    }

    this.mergeIntoMap((Map<String, Object>) accValue, deltaValue, nestedInfo);
    return accValue;
  }

  // ========== 컬렉션(List/Array) 병합 ==========

  private void mergeCollection(
      final List<Object> accList,
      final List<?> deltaList,
      final FieldMetadata field) {

    if (field.isPrimitiveList() || field.isPrimitiveArray()) {
      accList.addAll(deltaList);
      return;
    }

    final TypeInfo elementInfo = field.getElementTypeInfo();
    final String indexFieldName = this.resolveIndexFieldName(field, elementInfo);

    if (indexFieldName == null) {
      for (final Object deltaItem : deltaList) {
        accList.add(this.toMergingMap(deltaItem, elementInfo));
      }
      return;
    }

    for (final Object deltaItem : deltaList) {
      this.mergeObjectListItem(accList, deltaItem, elementInfo, indexFieldName);
    }
  }

  private String resolveIndexFieldName(final FieldMetadata field, final TypeInfo elementInfo) {
    final String listIndexFieldName = field.listIndexFieldName();

    if (listIndexFieldName != null) {
      final FieldMetadata indexField = elementInfo.findField(listIndexFieldName);
      if (indexField != null && isIntegerType(indexField.fieldType())) {
        return listIndexFieldName;
      }
      return null;
    }

    return elementInfo.indexFieldName();
  }

  private void mergeObjectListItem(
      final List<Object> accList,
      final Object deltaItem,
      final TypeInfo elementInfo,
      final String indexFieldName) {

    final Integer indexValue = getIndexValue(deltaItem, elementInfo, indexFieldName);

    if (elementInfo.hasCustomMerge()) {
      this.mergeCustomMergeListItem(accList, deltaItem, elementInfo, indexFieldName, indexValue);
      return;
    }

    final Map<String, Object> accItem = findMapByIndex(accList, indexFieldName, indexValue)
        .orElseGet(() -> {
          final Map<String, Object> newItem = new HashMap<>();
          accList.add(newItem);
          return newItem;
        });

    this.mergeIntoMap(accItem, deltaItem, elementInfo);
  }

  private void mergeCustomMergeListItem(
      final List<Object> accList,
      final Object deltaItem,
      final TypeInfo elementInfo,
      final String indexFieldName,
      final Integer indexValue) {

    final int existingIndex = findObjectIndexByValue(accList, elementInfo, indexFieldName, indexValue);

    if (existingIndex < 0) {
      accList.add(deltaItem);
    } else {
      try {
        final Object accItem = accList.get(existingIndex);
        accList.set(existingIndex, elementInfo.merge(accItem, deltaItem));
      } catch (final Exception e) {
        throw new MergeException("list element merge method invocation failed", e);
      }
    }
  }

  // ========== Map 병합 ==========

  @SuppressWarnings("unchecked")
  private void mergeMapField(
      final Map<String, Object> accMap,
      final Map<String, Object> deltaMap,
      final FieldMetadata field) {

    if (field.isPrimitiveValueMap()) {
      this.mergePrimitiveValueMap(accMap, deltaMap);
      return;
    }

    final TypeInfo valueInfo = field.getElementTypeInfo();

    for (final Map.Entry<String, Object> entry : deltaMap.entrySet()) {
      final String key = entry.getKey();
      final Object deltaItem = entry.getValue();

      if (deltaItem == null) {
        continue;
      }

      final Object accItem = accMap.get(key);

      if (accItem == null) {
        accMap.put(key, this.toMergingMap(deltaItem, valueInfo));
      } else if (accItem instanceof Map) {
        this.mergeIntoMap((Map<String, Object>) accItem, deltaItem, valueInfo);
      } else {
        accMap.put(key, deltaItem);
      }
    }
  }

  private void mergePrimitiveValueMap(
      final Map<String, Object> accMap,
      final Map<String, Object> deltaMap) {

    for (final Map.Entry<String, Object> entry : deltaMap.entrySet()) {
      final String key = entry.getKey();
      final Object deltaValue = entry.getValue();

      if (deltaValue == null) {
        continue;
      }

      final Object accValue = accMap.get(key);

      if (accValue == null) {
        accMap.put(key, deltaValue);
      } else if (accValue instanceof String accStr && deltaValue instanceof String deltaStr) {
        accMap.put(key, accStr + deltaStr);
      } else if (accValue instanceof Number accNum && deltaValue instanceof Number deltaNum) {
        accMap.put(key, sumNumbers(accNum, deltaNum));
      } else {
        accMap.put(key, deltaValue);
      }
    }
  }

  // ========== 값 복제 ==========

  @SuppressWarnings("unchecked")
  private Object cloneValue(final Object value, final FieldMetadata field) {
    if (value == null) {
      return null;
    }

    if (field.isObject()) {
      return this.toStorageForm(value, field.getFieldTypeInfo());
    }

    if (field.isList() && value instanceof List<?> list) {
      return this.cloneList(list, field);
    }

    if (field.isArray() && value.getClass().isArray()) {
      return this.cloneList(arrayToList(value), field);
    }

    if (field.isMap() && value instanceof Map) {
      return this.cloneMap((Map<String, Object>) value, field);
    }

    return value;
  }

  private List<Object> cloneList(final List<?> list, final FieldMetadata field) {
    if (field.isPrimitiveList() || field.isPrimitiveArray()) {
      return new ArrayList<>(list);
    }

    final TypeInfo elementInfo = field.getElementTypeInfo();
    final List<Object> clonedList = new ArrayList<>();

    for (final Object item : list) {
      clonedList.add(this.toStorageForm(item, elementInfo));
    }

    return clonedList;
  }

  private Map<String, Object> cloneMap(final Map<String, Object> map, final FieldMetadata field) {
    if (field.isPrimitiveValueMap()) {
      return new HashMap<>(map);
    }

    final TypeInfo valueInfo = field.getElementTypeInfo();
    final Map<String, Object> clonedMap = new HashMap<>();

    for (final Map.Entry<String, Object> entry : map.entrySet()) {
      if (entry.getValue() != null) {
        clonedMap.put(entry.getKey(), this.toMergingMap(entry.getValue(), valueInfo));
      }
    }

    return clonedMap;
  }

  // ========== 헬퍼 메서드 ==========

  /**
   * 객체를 Map으로 변환한다.
   */
  private Map<String, Object> toMergingMap(final Object value, final TypeInfo info) {
    final Map<String, Object> map = new HashMap<>();
    this.mergeIntoMap(map, value, info);
    return map;
  }

  /**
   * 객체를 저장 형태로 변환한다.
   * customMerge가 있으면 원본, 없으면 Map으로 변환.
   */
  private Object toStorageForm(final Object value, final TypeInfo info) {
    if (info.hasCustomMerge()) {
      return value;
    }
    return this.toMergingMap(value, info);
  }

  private static List<Object> arrayToList(final Object array) {
    final int length = java.lang.reflect.Array.getLength(array);
    final List<Object> list = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      list.add(java.lang.reflect.Array.get(array, i));
    }
    return list;
  }

  private static Integer getIndexValue(
      final Object item,
      final TypeInfo elementInfo,
      final String indexFieldName) {

    final FieldMetadata indexField = elementInfo.findField(indexFieldName);
    if (indexField == null) {
      return null;
    }

    final Object value = indexField.getValue(item);
    if (value instanceof Integer intValue) {
      return intValue;
    }
    if (value instanceof Number numValue) {
      return numValue.intValue();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static Optional<Map<String, Object>> findMapByIndex(
      final List<Object> list,
      final String indexFieldName,
      final Integer targetIndex) {

    if (targetIndex == null) {
      return Optional.empty();
    }

    for (final Object item : list) {
      if (item instanceof Map<?, ?> map && targetIndex.equals(map.get(indexFieldName))) {
        return Optional.of((Map<String, Object>) item);
      }
    }
    return Optional.empty();
  }

  private static int findObjectIndexByValue(
      final List<Object> list,
      final TypeInfo elementInfo,
      final String indexFieldName,
      final Integer targetIndex) {

    if (targetIndex == null) {
      return -1;
    }

    final FieldMetadata indexField = elementInfo.findField(indexFieldName);
    if (indexField == null) {
      return -1;
    }

    for (int i = 0; i < list.size(); i++) {
      final Object item = list.get(i);
      if (!(item instanceof Map) && targetIndex.equals(indexField.getValue(item))) {
        return i;
      }
    }
    return -1;
  }

  private static boolean isIntegerType(final Class<?> type) {
    return type == int.class || type == Integer.class;
  }

  private static Number sumNumbers(final Number a, final Number b) {
    if (a instanceof Double || b instanceof Double) {
      return a.doubleValue() + b.doubleValue();
    }
    if (a instanceof Long || b instanceof Long) {
      return a.longValue() + b.longValue();
    }
    return a.intValue() + b.intValue();
  }
}
