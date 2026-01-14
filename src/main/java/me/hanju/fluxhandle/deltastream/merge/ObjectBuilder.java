package me.hanju.fluxhandle.deltastream.merge;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.hanju.fluxhandle.deltastream.metadata.FieldMetadata;
import me.hanju.fluxhandle.deltastream.metadata.TypeMetadataCache;
import me.hanju.fluxhandle.deltastream.metadata.TypeMetadataCache.TypeInfo;

/**
 * Map 기반 저장소에서 객체를 생성하는 유틸리티.
 *
 * <p>
 * DeltaMerger는 병합 중에 Map으로 데이터를 저장한다.
 * 이 클래스는 그 Map을 실제 객체로 변환하는 역할을 한다.
 *
 * <h2>지원 타입</h2>
 * <ul>
 *   <li>일반 클래스: 기본 생성자 + setter 메서드</li>
 *   <li>Record 클래스: Canonical 생성자</li>
 * </ul>
 */
public final class ObjectBuilder {

  private ObjectBuilder() {
  }

  /**
   * 누적된 값들로부터 대상 타입의 인스턴스를 생성한다.
   *
   * <p>
   * 중첩된 Map은 해당하는 객체 타입으로 재귀적으로 변환된다.
   *
   * @param <T>    대상 타입
   * @param type   인스턴스화할 클래스
   * @param values 누적된 필드 값들 (Map 기반)
   * @return 생성된 인스턴스
   */
  public static <T> T build(final Class<T> type, final Map<String, Object> values) {
    return build(type, values, Map.of());
  }

  /**
   * TypeVariable 바인딩과 함께 대상 타입의 인스턴스를 생성한다.
   *
   * <p>
   * 제네릭 상속 구조에서 TypeVariable이 해석된 바인딩을 전달받아 사용한다.
   * 예를 들어 {@code Choice<CitedMessage>}를 빌드할 때
   * {@code {T -> CitedMessage}} 바인딩이 전달된다.
   *
   * @param <T>      대상 타입
   * @param type     인스턴스화할 클래스
   * @param values   누적된 필드 값들 (Map 기반)
   * @param bindings TypeVariable 바인딩 (부모에서 전달)
   * @return 생성된 인스턴스
   */
  public static <T> T build(
      final Class<T> type,
      final Map<String, Object> values,
      final Map<String, Type> bindings) {

    if (values == null) {
      return null;
    }

    // 바인딩이 비어있으면 타입에서 자동으로 바인딩을 추출
    // (예: CitedResponse.class → {T=CitedMessage} 추출)
    final TypeInfo typeInfo = (bindings == null || bindings.isEmpty())
        ? TypeMetadataCache.getTypeInfo(type)
        : TypeMetadataCache.getTypeInfo(type, bindings);

    // 중첩된 Map/List를 실제 객체 타입으로 변환
    final Map<String, Object> resolvedValues = resolveNestedValues(typeInfo, values);

    if (type.isRecord()) {
      // Record: Canonical 생성자 사용
      return buildRecord(type, resolvedValues);
    } else {
      // 일반 클래스: 기본 생성자 + 필드 설정
      return buildClass(type, resolvedValues, bindings);
    }
  }

  /**
   * 중첩된 Map/List를 실제 객체 타입으로 변환한다.
   */
  private static Map<String, Object> resolveNestedValues(
      final TypeInfo typeInfo,
      final Map<String, Object> values) {

    final Map<String, Object> resolved = new HashMap<>();

    for (final FieldMetadata field : typeInfo.fields()) {
      final Object value = values.get(field.fieldName());
      resolved.put(field.fieldName(), resolveFieldValue(field, value));
    }

    return resolved;
  }

  /**
   * 단일 필드 값을 실제 타입으로 변환한다.
   *
   * <ul>
   *   <li>Map -> 객체 (재귀, TypeVariable 바인딩 포함)</li>
   *   <li>List&lt;Map&gt; -> List&lt;객체&gt; (재귀)</li>
   *   <li>그 외 -> 그대로</li>
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private static Object resolveFieldValue(final FieldMetadata field, final Object value) {
    if (value == null) {
      return null;
    }

    // Map -> 객체 변환 (재귀, 해석된 필드 타입과 바인딩 사용)
    if (value instanceof Map<?, ?> nestedMap && field.isObject()) {
      // 해석된 필드 클래스와 바인딩을 사용 (TypeVariable 해석)
      final Class<?> resolvedClass = field.getResolvedFieldClass();
      final Map<String, Type> fieldBindings = field.getFieldTypeBindings();
      return build(resolvedClass, (Map<String, Object>) nestedMap, fieldBindings);
    }

    // List<Map> -> List<객체> 변환 (재귀)
    if (value instanceof List<?> list && field.isObjectList()) {
      return resolveObjectList(field, list);
    }

    // List -> Array 변환 (병합 중 List로 저장되었던 것을 Array로 변환)
    if (value instanceof List<?> list && field.isArray()) {
      return resolveArrayField(field, list);
    }

    // Map<String, Map> -> Map<String, 객체> 변환 (재귀)
    if (value instanceof Map<?, ?> map && field.isObjectValueMap()) {
      return resolveObjectValueMap(field, (Map<String, Object>) map);
    }

    return value;
  }

  /**
   * 객체 value Map의 각 value를 실제 객체로 변환한다.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolveObjectValueMap(
      final FieldMetadata field,
      final Map<String, Object> map) {

    final Map<String, Type> valueBindings = field.getElementTypeBindings();
    final Class<?> valueClass = field.getResolvedElementClass();

    final Map<String, Object> resolvedMap = new HashMap<>();
    for (final Map.Entry<String, Object> entry : map.entrySet()) {
      final Object value = entry.getValue();
      if (value instanceof Map<?, ?> valueMap) {
        // Map -> 객체로 변환 (바인딩과 함께)
        resolvedMap.put(entry.getKey(),
            build(valueClass, (Map<String, Object>) valueMap, valueBindings));
      } else {
        // 이미 객체면 그대로
        resolvedMap.put(entry.getKey(), value);
      }
    }
    return resolvedMap;
  }

  /**
   * 객체 List의 각 요소를 실제 객체로 변환한다.
   */
  @SuppressWarnings("unchecked")
  private static List<Object> resolveObjectList(final FieldMetadata field, final List<?> list) {
    // 해석된 요소 타입의 바인딩을 가져옴 (TypeVariable 해석)
    final Map<String, Type> elementBindings = field.getElementTypeBindings();
    final Class<?> elementClass = field.getResolvedElementClass();

    final List<Object> resolvedList = new ArrayList<>();
    for (final Object item : list) {
      if (item instanceof Map<?, ?> itemMap) {
        // Map -> 객체로 변환 (바인딩과 함께)
        resolvedList.add(build(elementClass, (Map<String, Object>) itemMap, elementBindings));
      } else {
        // 이미 객체면 그대로
        resolvedList.add(item);
      }
    }
    return resolvedList;
  }

  /**
   * List를 Array로 변환한다.
   * 병합 중 List로 저장되었던 데이터를 실제 Array 타입으로 변환한다.
   */
  @SuppressWarnings("unchecked")
  private static Object resolveArrayField(final FieldMetadata field, final List<?> list) {
    final Class<?> componentType = field.elementType();

    // primitive 배열: 특별 처리 필요
    if (componentType.isPrimitive()) {
      return resolvePrimitiveArray(list, componentType);
    }

    // 객체 배열
    final Object array = java.lang.reflect.Array.newInstance(componentType, list.size());

    if (field.isObjectArray()) {
      // 객체 배열: Map -> 객체 변환
      final Map<String, Type> elementBindings = field.getElementTypeBindings();
      final Class<?> elementClass = field.getResolvedElementClass();

      for (int i = 0; i < list.size(); i++) {
        final Object item = list.get(i);
        if (item instanceof Map<?, ?> itemMap) {
          final Object resolved = build(elementClass, (Map<String, Object>) itemMap, elementBindings);
          java.lang.reflect.Array.set(array, i, resolved);
        } else {
          java.lang.reflect.Array.set(array, i, item);
        }
      }
    } else {
      // primitive wrapper 배열 (String[], Integer[] 등)
      for (int i = 0; i < list.size(); i++) {
        java.lang.reflect.Array.set(array, i, list.get(i));
      }
    }

    return array;
  }

  /**
   * List를 primitive 배열로 변환한다.
   */
  private static Object resolvePrimitiveArray(final List<?> list, final Class<?> componentType) {
    final int size = list.size();

    if (componentType == int.class) {
      final int[] array = new int[size];
      for (int i = 0; i < size; i++) {
        array[i] = ((Number) list.get(i)).intValue();
      }
      return array;
    }
    if (componentType == long.class) {
      final long[] array = new long[size];
      for (int i = 0; i < size; i++) {
        array[i] = ((Number) list.get(i)).longValue();
      }
      return array;
    }
    if (componentType == double.class) {
      final double[] array = new double[size];
      for (int i = 0; i < size; i++) {
        array[i] = ((Number) list.get(i)).doubleValue();
      }
      return array;
    }
    if (componentType == float.class) {
      final float[] array = new float[size];
      for (int i = 0; i < size; i++) {
        array[i] = ((Number) list.get(i)).floatValue();
      }
      return array;
    }
    if (componentType == boolean.class) {
      final boolean[] array = new boolean[size];
      for (int i = 0; i < size; i++) {
        array[i] = (Boolean) list.get(i);
      }
      return array;
    }
    if (componentType == byte.class) {
      final byte[] array = new byte[size];
      for (int i = 0; i < size; i++) {
        array[i] = ((Number) list.get(i)).byteValue();
      }
      return array;
    }
    if (componentType == short.class) {
      final short[] array = new short[size];
      for (int i = 0; i < size; i++) {
        array[i] = ((Number) list.get(i)).shortValue();
      }
      return array;
    }
    if (componentType == char.class) {
      final char[] array = new char[size];
      for (int i = 0; i < size; i++) {
        array[i] = (Character) list.get(i);
      }
      return array;
    }

    throw new MergeException("Unsupported primitive array type: " + componentType, null);
  }

  /**
   * Record 인스턴스를 생성한다.
   *
   * <p>
   * Record는 Canonical 생성자만 있으므로 모든 필드 값을 순서대로 전달해야 함.
   */
  private static <T> T buildRecord(final Class<T> type, final Map<String, Object> values) {
    // Record의 컴포넌트 (필드) 정보
    final RecordComponent[] components = type.getRecordComponents();
    final Class<?>[] paramTypes = new Class<?>[components.length];
    final Object[] args = new Object[components.length];

    // 각 컴포넌트에 대해 타입과 값 준비
    for (int i = 0; i < components.length; i++) {
      paramTypes[i] = components[i].getType();
      final Object value = values.get(components[i].getName());
      // Number 타입 변환 (Long -> Integer 등)
      args[i] = convertValue(value, paramTypes[i]);
    }

    try {
      // Canonical 생성자 찾아서 호출
      final Constructor<T> constructor = type.getDeclaredConstructor(paramTypes);
      constructor.setAccessible(true);
      return constructor.newInstance(args);
    } catch (final Exception e) {
      throw new MergeException("Failed to build record: " + type.getName(), e);
    }
  }

  /**
   * 일반 클래스 인스턴스를 생성한다.
   *
   * <p>
   * 기본 생성자로 인스턴스 생성 후 setter 메서드로 값 설정.
   */
  private static <T> T buildClass(
      final Class<T> type,
      final Map<String, Object> values,
      final Map<String, Type> bindings) {

    try {
      // 기본 생성자로 인스턴스 생성
      final Constructor<T> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      final T instance = constructor.newInstance();

      // 각 필드에 setter로 값 설정
      final TypeInfo typeInfo = TypeMetadataCache.getTypeInfo(type, bindings);
      for (final FieldMetadata field : typeInfo.fields()) {
        final Object value = values.get(field.fieldName());
        if (value != null && field.setter() != null) {
          final Object converted = convertValue(value, field.fieldType());
          field.setter().accept(instance, converted);
        }
      }

      return instance;
    } catch (final MergeException e) {
      throw e;
    } catch (final Exception e) {
      throw new MergeException("Failed to build class: " + type.getName(), e);
    }
  }

  /**
   * 값을 대상 타입으로 변환한다.
   * 주로 Number 타입 간 변환에 사용.
   */
  private static Object convertValue(final Object value, final Class<?> targetType) {
    if (value == null) {
      return null;
    }

    // Number 타입 변환 (예: Long -> Integer)
    if (value instanceof Number number) {
      return convertNumber(number, targetType);
    }

    return value;
  }

  /**
   * Number를 대상 타입으로 변환한다.
   *
   * <p>
   * JSON 파싱 라이브러리에 따라 정수가 Long으로 오거나 Integer로 오는 경우가 있음.
   * 이를 대상 필드 타입에 맞게 변환.
   */
  private static Object convertNumber(final Number number, final Class<?> targetType) {
    if (targetType == Integer.class || targetType == int.class) {
      return number.intValue();
    }
    if (targetType == Long.class || targetType == long.class) {
      return number.longValue();
    }
    if (targetType == Double.class || targetType == double.class) {
      return number.doubleValue();
    }
    if (targetType == Float.class || targetType == float.class) {
      return number.floatValue();
    }
    return number;
  }
}
