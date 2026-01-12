package me.hanju.fluxhandle.deltastream.metadata;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;

import me.hanju.fluxhandle.deltastream.annotation.StreamIndex;

/**
 * 타입 메타데이터를 캐싱하여 반복적인 리플렉션을 방지하는 스레드 세이프 캐시.
 *
 * <p>
 * 클래스의 필드 정보, 인덱스 필드, 커스텀 merge 메서드 등을 캐싱한다.
 * 한 번 분석한 클래스는 {@link ConcurrentHashMap}에 저장되어 재사용된다.
 *
 * <h2>왜 캐싱이 필요한가?</h2>
 * 리플렉션은 비용이 큰 작업이다. 매번 {@code getDeclaredFields()},
 * {@code setAccessible(true)} 등을 호출하면 성능이 크게 저하된다.
 * 이 클래스는 클래스당 한 번만 리플렉션을 수행하고 결과를 캐시한다.
 */
public final class TypeMetadataCache {

  // 클래스 -> TypeInfo 매핑 캐시 (스레드 세이프)
  private static final ConcurrentHashMap<Class<?>, TypeInfo> CACHE = new ConcurrentHashMap<>();

  // Convention over Configuration: "index"라는 이름의 필드는 자동으로 인덱스 필드로 인식
  private static final String DEFAULT_INDEX_FIELD = "index";

  private TypeMetadataCache() {
  }

  /**
   * 주어진 타입의 메타데이터를 반환한다. 캐시에 없으면 계산 후 캐시에 저장.
   *
   * @param type 분석할 클래스
   * @return 타입 메타데이터
   */
  public static TypeInfo getTypeInfo(final Class<?> type) {
    // computeIfAbsent: 캐시에 없으면 computeTypeInfo 호출, 있으면 캐시된 값 반환
    return CACHE.computeIfAbsent(type, TypeMetadataCache::computeTypeInfo);
  }

  /**
   * 캐시를 비운다. 주로 테스트용.
   */
  public static void clearCache() {
    CACHE.clear();
  }

  /**
   * 클래스의 타입 정보를 계산한다.
   * Record와 일반 클래스를 구분하여 처리.
   */
  private static TypeInfo computeTypeInfo(final Class<?> type) {
    final List<FieldMetadata> fields = new ArrayList<>();

    // merge(T) 메서드가 있는지 찾기
    final Method mergeMethod = findMergeMethod(type);

    // 1단계: 인덱스 필드명 먼저 감지
    final String indexFieldName = detectIndexFieldName(type);

    if (type.isRecord()) {
      // === Record 타입 처리 ===
      // Record는 immutable이므로 setter 없음, canonical constructor로 생성
      for (final RecordComponent component : type.getRecordComponents()) {
        final Field field = getRecordField(type, component.getName());
        final Method accessor = component.getAccessor();

        final Class<?> elementType = extractElementType(component.getGenericType());
        final boolean isIndexField = component.getName().equals(indexFieldName);

        fields.add(FieldMetadata.of(
            component.getName(),
            component.getType(),
            elementType,
            field,
            accessor,
            null, // Record는 setter 없음
            isIndexField));
      }
    } else {
      // === 일반 클래스 처리 ===
      for (final Field field : getAllFields(type)) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
          continue;
        }

        final Method getter = findGetter(type, field.getName(), field.getType());
        final Method setter = findSetter(type, field.getName(), field.getType());

        final Class<?> elementType = extractElementType(field.getGenericType());
        final boolean isIndexField = field.getName().equals(indexFieldName);

        fields.add(FieldMetadata.of(
            field.getName(),
            field.getType(),
            elementType,
            field,
            getter,
            setter,
            isIndexField));
      }
    }

    final BinaryOperator<Object> mergeFunction = createMergeFunction(mergeMethod);
    return new TypeInfo(List.copyOf(fields), indexFieldName, mergeFunction);
  }

  private static String detectIndexFieldName(final Class<?> type) {
    if (type.isRecord()) {
      for (final RecordComponent component : type.getRecordComponents()) {
        final Field field = getRecordField(type, component.getName());
        if (field != null && field.isAnnotationPresent(StreamIndex.class)) {
          return component.getName();
        }
      }
      for (final RecordComponent component : type.getRecordComponents()) {
        if (component.getName().equals(DEFAULT_INDEX_FIELD)) {
          return DEFAULT_INDEX_FIELD;
        }
      }
    } else {
      for (final Field field : getAllFields(type)) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
          continue;
        }
        if (field.isAnnotationPresent(StreamIndex.class)) {
          return field.getName();
        }
      }
      for (final Field field : getAllFields(type)) {
        if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
          continue;
        }
        if (field.getName().equals(DEFAULT_INDEX_FIELD)) {
          return DEFAULT_INDEX_FIELD;
        }
      }
    }
    return null;
  }

  /**
   * 클래스에서 merge(T) 메서드를 찾는다.
   * 시그니처: T merge(T delta) - 같은 타입을 받아 같은 타입을 반환
   */
  private static Method findMergeMethod(final Class<?> type) {
    try {
      final Method method = type.getDeclaredMethod("merge", type);
      // 반환 타입 검증: 자기 자신 또는 부모 타입
      if (method.getReturnType() == type
          || method.getReturnType().isAssignableFrom(type)) {
        return method;
      }
    } catch (final NoSuchMethodException e) {
      // merge 메서드 없음 - 기본 병합 규칙 사용
    }
    return null;
  }

  /**
   * merge 메서드를 최적화된 BinaryOperator로 생성한다
   */
  private static BinaryOperator<Object> createMergeFunction(final Method mergeMethod) {
    if (mergeMethod == null) {
      return null;
    }

    try {
      // privateLookupIn: 대상 클래스의 lookup 권한을 획득
      final Class<?> declaringClass = mergeMethod.getDeclaringClass();
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
          declaringClass, MethodHandles.lookup());

      // Method -> MethodHandle 변환
      final MethodHandle handle = lookup.unreflect(mergeMethod);

      // 타입 어댑터: (T, T) -> T 를 (Object, Object) -> Object로 변환
      // 이렇게 해야 제네릭 BinaryOperator<Object>와 호환됨
      final MethodHandle adapted = handle.asType(
          MethodType.methodType(Object.class, Object.class, Object.class));

      // LambdaMetafactory로 BinaryOperator 인터페이스 구현체 생성
      // 내부적으로 invokedynamic 명령어와 같은 방식으로 동작
      final CallSite site = LambdaMetafactory.metafactory(
          lookup,
          "apply", // BinaryOperator의 메서드명
          MethodType.methodType(BinaryOperator.class), // 생성할 람다의 타입
          MethodType.methodType(Object.class, Object.class, Object.class), // SAM의 시그니처
          adapted, // 실제 구현체 (MethodHandle)
          MethodType.methodType(Object.class, Object.class, Object.class) // 런타임 시그니처
      );

      // CallSite에서 실제 람다 인스턴스 추출
      return (BinaryOperator<Object>) site.getTarget().invokeExact();

    } catch (final Throwable e) {
      // LambdaMetafactory 실패 시 리플렉션으로 폴백
      return (acc, delta) -> {
        try {
          return mergeMethod.invoke(acc, delta);
        } catch (final Exception ex) {
          throw new MetadataException("Failed to invoke merge method", ex);
        }
      };
    }
  }

  /**
   * Record 클래스에서 필드를 가져온다.
   */
  private static Field getRecordField(final Class<?> type, final String name) {
    try {
      return type.getDeclaredField(name);
    } catch (final NoSuchFieldException e) {
      return null;
    }
  }

  /**
   * 필드에 대한 getter 메서드를 찾는다.
   */
  private static Method findGetter(
      final Class<?> type,
      final String fieldName,
      final Class<?> fieldType) {

    // 1. Record 스타일: name()
    try {
      return type.getMethod(fieldName);
    } catch (final NoSuchMethodException ignored) {
    }

    // 2. JavaBean 스타일: getName()
    final String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    try {
      return type.getMethod("get" + capitalized);
    } catch (final NoSuchMethodException ignored) {
    }

    // 3. boolean: isName()
    if (fieldType == boolean.class || fieldType == Boolean.class) {
      try {
        return type.getMethod("is" + capitalized);
      } catch (final NoSuchMethodException ignored) {
      }
    }

    return null;
  }

  /**
   * 필드에 대한 setter 메서드를 찾는다.
   */
  private static Method findSetter(
      final Class<?> type,
      final String fieldName,
      final Class<?> fieldType) {

    final String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    try {
      return type.getMethod("set" + capitalized, fieldType);
    } catch (final NoSuchMethodException ignored) {
    }

    return null;
  }

  /**
   * 상속 계층의 모든 필드를 수집한다.
   * 부모 클래스의 필드도 포함해야 하므로 계층을 순회.
   */
  private static List<Field> getAllFields(final Class<?> type) {
    final List<Field> fields = new ArrayList<>();
    Class<?> current = type;
    // Object 클래스까지 올라가며 모든 필드 수집
    while (current != null && current != Object.class) {
      Collections.addAll(fields, current.getDeclaredFields());
      current = current.getSuperclass();
    }
    return fields;
  }

  /**
   * 제네릭 타입에서 요소 타입을 추출한다.
   * 예: List<String> -> String, List<Map<String, Object>> -> Map
   */
  private static Class<?> extractElementType(final Type genericType) {
    // ParameterizedType: List<String> 같은 제네릭 타입
    if (genericType instanceof ParameterizedType pt) {
      final Type[] typeArgs = pt.getActualTypeArguments();

      // 첫 번째 타입 파라미터가 Class인 경우 (예: List<String>)
      if (typeArgs.length > 0 && typeArgs[0] instanceof Class<?> elemClass) {
        return elemClass;
      }

      // 중첩된 ParameterizedType인 경우 (예: List<Map<String, Object>>)
      if (typeArgs.length > 0 && typeArgs[0] instanceof ParameterizedType nestedPt) {
        final Type rawType = nestedPt.getRawType();
        if (rawType instanceof Class<?> rawClass) {
          return rawClass;
        }
      }
    }
    return null;
  }

  /**
   * 타입에 대한 메타데이터.
   *
   * @param fields         필드 메타데이터 목록
   * @param indexFieldName 인덱스 필드명 (@StreamIndex 또는 "index")
   * @param mergeFunction  최적화된 merge 함수 (LambdaMetafactory로 생성)
   */
  public record TypeInfo(
      List<FieldMetadata> fields,
      String indexFieldName,
      BinaryOperator<Object> mergeFunction) {

    /**
     * 커스텀 merge 메서드가 있는지 확인.
     */
    public boolean hasCustomMerge() {
      return mergeFunction != null;
    }

    /**
     * 커스텀 merge 함수를 실행한다.
     *
     * @param accumulated 누적된 객체
     * @param delta       병합할 델타
     * @return 병합된 결과
     */
    public Object merge(final Object accumulated, final Object delta) {
      if (mergeFunction == null) {
        throw new IllegalStateException("No custom merge function available");
      }
      return mergeFunction.apply(accumulated, delta);
    }

    /**
     * 이름으로 필드를 찾는다.
     *
     * @param name 필드 이름
     * @return 필드 메타데이터, 없으면 null
     */
    public FieldMetadata findField(final String name) {
      for (final FieldMetadata field : fields) {
        if (field.fieldName().equals(name)) {
          return field;
        }
      }
      return null;
    }
  }
}
