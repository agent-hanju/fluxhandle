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
 * @param <T> 병합할 델타 객체의 타입
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
    // 캐시에서 타입 메타데이터 조회 (한 번만 리플렉션 수행)
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
    if (typeInfo.hasCustomMerge()) {
      applyCustomMerge(delta);
    } else {
      mergeIntoMap(this.mergingMap, delta, this.typeInfo);
    }
  }

  /**
   * 최종 병합된 결과를 생성하여 반환한다.
   *
   * @return 병합된 결과 객체
   */
  public T build() {
    if (typeInfo.hasCustomMerge()) {
      return merged;
    }
    return ObjectBuilder.build(this.type, this.mergingMap);
  }

  /**
   * 커스텀 merge 메서드를 사용하여 병합한다.
   * 첫 번째 델타는 그대로 저장하고, 이후부터 merge 호출.
   */
  @SuppressWarnings("unchecked")
  private void applyCustomMerge(final T delta) {
    if (merged == null) {
      // 첫 델타: 그대로 저장
      merged = delta;
    } else {
      // 이후 델타: merge 메서드 호출
      try {
        merged = (T) typeInfo.merge(merged, delta);
      } catch (final Exception e) {
        throw new MergeException("merge method invocation failed", e);
      }
    }
  }

  /**
   * 델타 객체의 필드들을 Map에 병합한다.
   *
   * @param acc   누적 Map
   * @param delta 병합할 델타 객체
   * @param info  타입 메타데이터
   */
  private void mergeIntoMap(
      final Map<String, Object> acc,
      final Object delta,
      final TypeInfo info) {

    for (final FieldMetadata field : info.fields()) {
      final Object deltaValue = field.getValue(delta);
      // null 값은 무시 (스트리밍에서 "변경 없음"을 의미)
      if (deltaValue != null) {
        mergeField(acc, field, deltaValue);
      }
    }
  }

  /**
   * 단일 필드를 병합한다.
   */
  private void mergeField(
      final Map<String, Object> acc,
      final FieldMetadata field,
      final Object deltaValue) {

    final String key = field.fieldName();
    final Object accValue = acc.get(key);

    // 누적 값이 없으면 델타 값을 복사하여 저장
    if (accValue == null) {
      acc.put(key, cloneValue(deltaValue, field));
      return;
    }

    // @StreamIndex 또는 @StreamOverwrite 필드는 항상 덮어쓰기
    if (field.isSpecialKey()) {
      acc.put(key, deltaValue);
      return;
    }

    // 타입별 병합 규칙 적용
    final Object mergedValue = computeMergedValue(field, accValue, deltaValue);
    acc.put(key, mergedValue);
  }

  /**
   * 필드 타입에 따라 적절한 병합 연산을 수행한다.
   */
  private Object computeMergedValue(
      final FieldMetadata field,
      final Object accValue,
      final Object deltaValue) {

    // String: 연결 (concatenation)
    if (field.isString() && deltaValue instanceof String deltaStr) {
      return (String) accValue + deltaStr;
    }

    // Number: 합산 (addition)
    if (field.isNumber() && deltaValue instanceof Number deltaNum) {
      return sumNumbers((Number) accValue, deltaNum);
    }

    // Object: 재귀적 병합
    if (field.isObject() && accValue instanceof Map) {
      mergeNestedObject(field, accValue, deltaValue);
      return accValue;
    }

    // List: 확장 또는 index 기반 병합
    if (field.isList() && accValue instanceof List && deltaValue instanceof List) {
      mergeListField(field, accValue, deltaValue);
      return accValue;
    }

    // 그 외: 덮어쓰기
    return deltaValue;
  }

  /**
   * 중첩 객체를 재귀적으로 병합한다.
   */
  @SuppressWarnings("unchecked")
  private void mergeNestedObject(
      final FieldMetadata field,
      final Object accValue,
      final Object deltaValue) {

    final Map<String, Object> accMap = (Map<String, Object>) accValue;
    final TypeInfo nestedInfo = TypeMetadataCache.getTypeInfo(field.fieldType());
    mergeIntoMap(accMap, deltaValue, nestedInfo);
  }

  /**
   * List 필드를 병합한다.
   */
  @SuppressWarnings("unchecked")
  private void mergeListField(
      final FieldMetadata field,
      final Object accValue,
      final Object deltaValue) {

    final List<Object> accList = (List<Object>) accValue;
    mergeList(accList, (List<?>) deltaValue, field);
  }

  /**
   * List를 병합한다.
   * - 기본 타입 List: 단순 확장 (addAll)
   * - 객체 List: index 기반 병합
   */
  private void mergeList(
      final List<Object> accList,
      final List<?> deltaList,
      final FieldMetadata field) {

    // 기본 타입 List: 뒤에 추가
    if (field.isPrimitiveList()) {
      accList.addAll(deltaList);
      return;
    }

    // 객체 List: index 기반 병합
    final TypeInfo elementInfo = TypeMetadataCache.getTypeInfo(field.elementType());
    final String indexFieldName = elementInfo.indexFieldName();

    // 객체 List는 반드시 index 필드가 있어야 함
    if (indexFieldName == null) {
      throw new MergeException(
          "index field required for object list element: " + field.elementType().getName()
              + ". Use @StreamIndex annotation or add an 'index' field.",
          null);
    }

    // 각 델타 아이템을 index로 찾아서 병합
    for (final Object deltaItem : deltaList) {
      mergeObjectListItem(accList, deltaItem, elementInfo, indexFieldName);
    }
  }

  /**
   * 객체 List의 단일 아이템을 index 기반으로 병합한다.
   */
  private void mergeObjectListItem(
      final List<Object> accList,
      final Object deltaItem,
      final TypeInfo elementInfo,
      final String indexFieldName) {

    // 델타 아이템의 index 값 추출
    final Integer indexValue = getIndexValue(deltaItem, elementInfo);

    // 누적 List에서 같은 index를 가진 아이템 찾기
    final Optional<Map<String, Object>> existingItem = findByIndex(accList, indexFieldName, indexValue);

    // 없으면 새 Map 추가, 있으면 기존 Map에 병합
    final Map<String, Object> accItem = existingItem.orElseGet(() -> {
      final Map<String, Object> newItem = new HashMap<>();
      accList.add(newItem);
      return newItem;
    });

    mergeIntoMap(accItem, deltaItem, elementInfo);
  }

  /**
   * 객체에서 index 필드 값을 추출한다.
   */
  private Integer getIndexValue(final Object item, final TypeInfo elementInfo) {
    final String indexFieldName = elementInfo.indexFieldName();
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

  /**
   * List에서 특정 index 값을 가진 아이템을 찾는다.
   */
  @SuppressWarnings("unchecked")
  private Optional<Map<String, Object>> findByIndex(
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

  /**
   * 값을 복사한다. Object와 List는 깊은 복사가 필요.
   */
  private Object cloneValue(final Object value, final FieldMetadata field) {
    if (value == null) {
      return null;
    }

    // 객체: Map으로 변환하며 복사
    if (field.isObject()) {
      return cloneObject(value, field);
    }

    // List: 새 List로 복사
    if (field.isList() && value instanceof List<?> list) {
      return cloneList(list, field);
    }

    // 기본 타입: 불변이므로 그대로 반환
    return value;
  }

  /**
   * 객체를 Map으로 변환하며 복사한다.
   */
  private Map<String, Object> cloneObject(final Object value, final FieldMetadata field) {
    final Map<String, Object> map = new HashMap<>();
    final TypeInfo nestedInfo = TypeMetadataCache.getTypeInfo(field.fieldType());
    mergeIntoMap(map, value, nestedInfo);
    return map;
  }

  /**
   * List를 복사한다.
   */
  private List<Object> cloneList(final List<?> list, final FieldMetadata field) {
    // 기본 타입 List: 얕은 복사로 충분
    if (field.isPrimitiveList()) {
      return new ArrayList<>(list);
    }

    // 객체 List: 각 요소를 Map으로 변환하며 복사
    final TypeInfo elementInfo = TypeMetadataCache.getTypeInfo(field.elementType());
    final String indexFieldName = elementInfo.indexFieldName();

    if (indexFieldName == null) {
      throw new MergeException(
          "index field required for object list element: " + field.elementType().getName()
              + ". Use @StreamIndex annotation or add an 'index' field.",
          null);
    }

    final List<Object> clonedList = new ArrayList<>();
    for (final Object item : list) {
      final Map<String, Object> itemMap = new HashMap<>();
      mergeIntoMap(itemMap, item, elementInfo);
      clonedList.add(itemMap);
    }
    return clonedList;
  }

  /**
   * 두 Number를 합산한다.
   * 타입 승격: Double > Long > Integer
   */
  private Number sumNumbers(final Number a, final Number b) {
    if (a instanceof Double || b instanceof Double) {
      return a.doubleValue() + b.doubleValue();
    }
    if (a instanceof Long || b instanceof Long) {
      return a.longValue() + b.longValue();
    }
    return a.intValue() + b.intValue();
  }
}
