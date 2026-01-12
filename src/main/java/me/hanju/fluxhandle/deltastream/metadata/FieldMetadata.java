package me.hanju.fluxhandle.deltastream.metadata;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

import me.hanju.fluxhandle.deltastream.annotation.StreamOverwrite;


/**
 * 단일 필드에 대한 캐시된 메타데이터.
 *
 * <p>
 * 델타 병합 중 반복적인 리플렉션 호출을 피하기 위해 필드 정보를 미리 계산해둔다.
 * 필드 접근은 {@link MethodHandle}과 {@link LambdaMetafactory}를 사용하여
 * 직접 호출에 가까운 성능으로 최적화된다.
 *
 * <h2>성능 최적화</h2>
 * <ul>
 *   <li>기존 리플렉션 (Field.get): 직접 호출 대비 ~10x 느림</li>
 *   <li>MethodHandle: 직접 호출 대비 ~2x 느림</li>
 *   <li>LambdaMetafactory: 직접 호출과 거의 동일</li>
 * </ul>
 *
 * @param fieldName    필드 이름
 * @param fieldType    필드 타입
 * @param elementType  List 필드의 경우 요소 타입, 아니면 null
 * @param getter       LambdaMetafactory로 생성한 최적화된 getter
 * @param setter       LambdaMetafactory로 생성한 최적화된 setter (Record는 null)
 * @param isSpecialKey 인덱스 필드 또는 @StreamOverwrite 필드 여부 (병합 시 덮어쓰기)
 */
public record FieldMetadata(
    String fieldName,
    Class<?> fieldType,
    Class<?> elementType,
    Function<Object, Object> getter,
    BiConsumer<Object, Object> setter,
    boolean isSpecialKey
) {

  // 기본 타입: List 요소가 기본 타입이면 extend(추가), 아니면 index-based merge
  private static final Set<Class<?>> PRIMITIVE_TYPES = Set.of(
      String.class, Integer.class, Long.class, Double.class, Float.class,
      Boolean.class, int.class, long.class, double.class, float.class, boolean.class
  );

  /**
   * 최적화된 getter/setter를 포함한 FieldMetadata 인스턴스를 생성한다.
   *
   * <p>
   * {@link LambdaMetafactory}를 사용하여 고성능 getter/setter를 생성한다.
   * invokedynamic과 같은 메커니즘을 사용하여 직접 메서드 호출과 거의 동일한 성능을 보장한다.
   *
   * @param fieldName      필드 이름
   * @param fieldType      필드 타입
   * @param elementType    List 요소 타입
   * @param field          Field 객체 (@StreamOverwrite 어노테이션 확인용)
   * @param getterMethod   getter 메서드 (필수)
   * @param setterMethod   setter 메서드 (Record는 null)
   * @param isIndexField   인덱스 필드 여부 (@StreamIndex 또는 "index" 이름)
   * @return 생성된 FieldMetadata
   */
  public static FieldMetadata of(
      final String fieldName,
      final Class<?> fieldType,
      final Class<?> elementType,
      final Field field,
      final Method getterMethod,
      final Method setterMethod,
      final boolean isIndexField) {

    final Function<Object, Object> getter = createGetter(fieldName, getterMethod);
    final BiConsumer<Object, Object> setter = createSetter(fieldName, setterMethod);

    final boolean isSpecialKey = isIndexField
        || (field != null && field.isAnnotationPresent(StreamOverwrite.class));

    return new FieldMetadata(
        fieldName,
        fieldType,
        elementType,
        getter,
        setter,
        isSpecialKey
    );
  }

  /**
   * LambdaMetafactory를 사용하여 최적화된 getter를 생성한다.
   *
   * @param fieldName 필드명 (오류 메시지용)
   * @param accessor  getter 메서드 (필수)
   * @throws MetadataException getter가 없거나 LambdaMetafactory 실패 시
   */
  private static Function<Object, Object> createGetter(
      final String fieldName,
      final Method accessor) {

    if (accessor == null) {
      throw new MetadataException(
          "No getter method found for field: " + fieldName
              + ". Add a public getter (getName() or name()).", null);
    }

    try {
      // privateLookupIn: 대상 클래스의 lookup 권한을 획득
      final Class<?> declaringClass = accessor.getDeclaringClass();
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
          declaringClass, MethodHandles.lookup());
      final MethodHandle handle = lookup.unreflect(accessor);

      // LambdaMetafactory로 고성능 Function 생성
      final CallSite site = LambdaMetafactory.metafactory(
          lookup,
          "apply",
          MethodType.methodType(Function.class),
          MethodType.methodType(Object.class, Object.class),  // SAM 시그니처
          handle,                                              // direct 핸들
          handle.type()                                        // 실제 시그니처
      );

      return (Function<Object, Object>) site.getTarget().invokeExact();

    } catch (final Throwable e) {
      throw new MetadataException("Failed to create getter for field: " + fieldName, e);
    }
  }

  /**
   * LambdaMetafactory를 사용하여 최적화된 setter를 생성한다.
   *
   * @param fieldName     필드명 (오류 메시지용)
   * @param setterMethod  setter 메서드 (Record는 null)
   * @return 최적화된 BiConsumer, setter가 없으면 null
   */
  private static BiConsumer<Object, Object> createSetter(
      final String fieldName,
      final Method setterMethod) {

    if (setterMethod == null) {
      return null;
    }

    try {
      final Class<?> declaringClass = setterMethod.getDeclaringClass();
      final MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
          declaringClass, MethodHandles.lookup());
      final MethodHandle handle = lookup.unreflect(setterMethod);

      // BiConsumer로 생성: (Object target, Object value) -> void
      final CallSite site = LambdaMetafactory.metafactory(
          lookup,
          "accept",
          MethodType.methodType(BiConsumer.class),
          MethodType.methodType(void.class, Object.class, Object.class),
          handle,
          handle.type()
      );

      return (BiConsumer<Object, Object>) site.getTarget().invokeExact();

    } catch (final Throwable e) {
      throw new MetadataException("Failed to create setter for field: " + fieldName, e);
    }
  }

  /**
   * 소스 객체에서 이 필드의 값을 가져온다.
   *
   * <p>
   * 미리 생성된 최적화된 getter를 사용하여 최상의 성능을 보장한다.
   *
   * @param source 값을 읽을 소스 객체
   * @return 필드 값, source가 null이면 null
   */
  public Object getValue(final Object source) {
    if (source == null) {
      return null;
    }
    try {
      return getter.apply(source);
    } catch (final MetadataException e) {
      throw e;
    } catch (final Exception e) {
      throw new MetadataException(
          "Failed to get field value: " + fieldName, e);
    }
  }

  /**
   * List 타입인지 확인.
   */
  public boolean isList() {
    return List.class.isAssignableFrom(fieldType);
  }

  /**
   * 기본 타입의 List인지 확인 (String, Number, Boolean).
   * 기본 타입 리스트는 병합 시 단순히 extend(뒤에 추가)된다.
   */
  public boolean isPrimitiveList() {
    return isList() && elementType != null && PRIMITIVE_TYPES.contains(elementType);
  }

  /**
   * 객체 타입의 List인지 확인 (기본 타입이 아닌 것).
   * 객체 리스트는 index 기반으로 병합된다.
   */
  public boolean isObjectList() {
    return isList() && elementType != null && !PRIMITIVE_TYPES.contains(elementType);
  }

  /**
   * Number 타입인지 확인.
   * Number 필드는 병합 시 합산된다.
   */
  public boolean isNumber() {
    return Number.class.isAssignableFrom(fieldType)
        || fieldType == int.class
        || fieldType == long.class
        || fieldType == double.class
        || fieldType == float.class;
  }

  /**
   * String 타입인지 확인.
   * String 필드는 병합 시 연결(concatenation)된다.
   */
  public boolean isString() {
    return fieldType == String.class;
  }

  /**
   * 복합 객체 타입인지 확인 (기본 타입도 아니고 List도 아닌 것).
   * 객체 필드는 재귀적으로 병합된다.
   */
  public boolean isObject() {
    return !PRIMITIVE_TYPES.contains(fieldType) && !isList();
  }
}
