package me.hanju.fluxhandle.deltastream.merge;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
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
    if (values == null) {
      return null;
    }

    final TypeInfo typeInfo = TypeMetadataCache.getTypeInfo(type);

    // 중첩된 Map/List를 실제 객체 타입으로 변환
    final Map<String, Object> resolvedValues = resolveNestedValues(typeInfo, values);

    if (type.isRecord()) {
      // Record: Canonical 생성자 사용
      return buildRecord(type, resolvedValues);
    } else {
      // 일반 클래스: 기본 생성자 + 필드 설정
      return buildClass(type, resolvedValues);
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
   *   <li>Map -> 객체 (재귀)</li>
   *   <li>List&lt;Map&gt; -> List&lt;객체&gt; (재귀)</li>
   *   <li>그 외 -> 그대로</li>
   * </ul>
   */
  @SuppressWarnings("unchecked")
  private static Object resolveFieldValue(final FieldMetadata field, final Object value) {
    if (value == null) {
      return null;
    }

    // Map -> 객체 변환 (재귀)
    if (value instanceof Map<?, ?> nestedMap && field.isObject()) {
      return build(field.fieldType(), (Map<String, Object>) nestedMap);
    }

    // List<Map> -> List<객체> 변환 (재귀)
    if (value instanceof List<?> list && field.isObjectList()) {
      return resolveObjectList(field, list);
    }

    return value;
  }

  /**
   * 객체 List의 각 요소를 실제 객체로 변환한다.
   */
  @SuppressWarnings("unchecked")
  private static List<Object> resolveObjectList(final FieldMetadata field, final List<?> list) {
    final List<Object> resolvedList = new ArrayList<>();
    for (final Object item : list) {
      if (item instanceof Map<?, ?> itemMap) {
        // Map -> 객체로 변환
        resolvedList.add(build(field.elementType(), (Map<String, Object>) itemMap));
      } else {
        // 이미 객체면 그대로
        resolvedList.add(item);
      }
    }
    return resolvedList;
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
  private static <T> T buildClass(final Class<T> type, final Map<String, Object> values) {
    try {
      // 기본 생성자로 인스턴스 생성
      final Constructor<T> constructor = type.getDeclaredConstructor();
      constructor.setAccessible(true);
      final T instance = constructor.newInstance();

      // 각 필드에 setter로 값 설정
      final TypeInfo typeInfo = TypeMetadataCache.getTypeInfo(type);
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
