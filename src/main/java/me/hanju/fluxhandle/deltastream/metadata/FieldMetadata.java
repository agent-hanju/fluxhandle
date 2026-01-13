package me.hanju.fluxhandle.deltastream.metadata;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
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
 * @param fieldName           필드 이름
 * @param fieldType           필드 타입 (런타임 타입, TypeVariable은 Object로 소거됨)
 * @param resolvedFieldType   해석된 필드 타입 (TypeVariable이 실제 타입으로 치환됨)
 * @param elementType         List 필드의 경우 요소 타입 (raw Class), 아니면 null
 * @param resolvedElementType List 필드의 경우 해석된 요소 타입 (제네릭 정보 포함), 아니면 null
 * @param getter              LambdaMetafactory로 생성한 최적화된 getter
 * @param setter              LambdaMetafactory로 생성한 최적화된 setter (Record는 null)
 * @param isSpecialKey        인덱스 필드 또는 @StreamOverwrite 필드 여부 (병합 시 덮어쓰기)
 */
public record FieldMetadata(
    String fieldName,
    Class<?> fieldType,
    Type resolvedFieldType,
    Class<?> elementType,
    Type resolvedElementType,
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
   * @param fieldName           필드 이름
   * @param fieldType           필드 타입 (런타임 타입)
   * @param resolvedFieldType   해석된 필드 타입 (TypeVariable이 실제 타입으로 치환됨)
   * @param elementType         List 요소 타입 (raw Class)
   * @param resolvedElementType List 요소의 해석된 타입 (제네릭 정보 포함)
   * @param field               Field 객체 (@StreamOverwrite 어노테이션 확인용)
   * @param getterMethod        getter 메서드 (필수)
   * @param setterMethod        setter 메서드 (Record는 null)
   * @param isIndexField        인덱스 필드 여부 (@StreamIndex 또는 "index" 이름)
   * @return 생성된 FieldMetadata
   */
  public static FieldMetadata of(
      final String fieldName,
      final Class<?> fieldType,
      final Type resolvedFieldType,
      final Class<?> elementType,
      final Type resolvedElementType,
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
        resolvedFieldType,
        elementType,
        resolvedElementType,
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

      // primitive 타입 반환 시 wrapper 타입으로 변환하여 boxing 지원
      // LambdaMetafactory는 instantiatedMethodType에서 wrapper 타입을 보면 자동 boxing
      final Class<?> returnType = accessor.getReturnType();
      final Class<?> boxedReturnType = box(returnType);

      // LambdaMetafactory로 고성능 Function 생성
      final CallSite site = LambdaMetafactory.metafactory(
          lookup,
          "apply",
          MethodType.methodType(Function.class),
          MethodType.methodType(Object.class, Object.class),  // SAM 시그니처
          handle,                                              // direct 핸들
          MethodType.methodType(boxedReturnType, declaringClass) // boxing 적용 시그니처
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

      // primitive 타입 파라미터 시 wrapper 타입으로 변환하여 unboxing 지원
      // LambdaMetafactory는 instantiatedMethodType에서 wrapper 타입을 보면 자동 unboxing
      final Class<?> paramType = setterMethod.getParameterTypes()[0];
      final Class<?> boxedParamType = box(paramType);

      // BiConsumer로 생성: (Object target, Object value) -> void
      final CallSite site = LambdaMetafactory.metafactory(
          lookup,
          "accept",
          MethodType.methodType(BiConsumer.class),
          MethodType.methodType(void.class, Object.class, Object.class),
          handle,
          MethodType.methodType(void.class, declaringClass, boxedParamType) // unboxing 적용 시그니처
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
   * primitive 타입인지 확인 (int, long, double, float, boolean 등).
   * primitive 필드는 null을 표현할 수 없으므로 병합 시 항상 덮어쓰기된다.
   */
  public boolean isPrimitiveType() {
    return fieldType.isPrimitive();
  }

  /**
   * 복합 객체 타입인지 확인 (기본 타입도 아니고 List도 아닌 것).
   * 객체 필드는 재귀적으로 병합된다.
   */
  public boolean isObject() {
    return !PRIMITIVE_TYPES.contains(fieldType) && !isList();
  }

  /**
   * 해석된 필드 타입의 raw Class를 반환한다.
   *
   * <p>
   * TypeVariable 바인딩이 해석된 후의 실제 Class를 반환한다.
   * 예를 들어 {@code T message}에서 T가 CitedMessage로 바인딩된 경우
   * {@code CitedMessage.class}를 반환한다.
   *
   * @return 해석된 필드의 raw Class, 없으면 fieldType 반환
   */
  public Class<?> getResolvedFieldClass() {
    if (resolvedFieldType == null) {
      return fieldType;
    }
    final Class<?> resolved = TypeVariableResolver.getRawClass(resolvedFieldType);
    return resolved != null ? resolved : fieldType;
  }

  /**
   * 해석된 필드 타입의 TypeVariable 바인딩을 반환한다.
   *
   * <p>
   * 중첩된 제네릭 타입 처리에 필요하다.
   * 예를 들어 필드 타입이 {@code Container<String>}인 경우
   * {@code {T -> String}} 바인딩을 추출한다.
   *
   * @return TypeVariable 이름 -> Type 매핑, 바인딩이 없으면 빈 맵
   */
  public java.util.Map<String, Type> getFieldTypeBindings() {
    if (resolvedFieldType == null) {
      return java.util.Map.of();
    }
    return TypeVariableResolver.extractBindingsFromType(resolvedFieldType);
  }

  /**
   * 해석된 요소 타입의 raw Class를 반환한다.
   *
   * <p>
   * TypeVariable 바인딩이 해석된 후의 실제 Class를 반환한다.
   * 예를 들어 {@code List<Choice<CitedMessage>>}의 경우 {@code Choice.class}를 반환한다.
   *
   * @return 해석된 요소의 raw Class, 없으면 elementType 반환
   */
  public Class<?> getResolvedElementClass() {
    if (resolvedElementType == null) {
      return elementType;
    }
    final Class<?> resolved = TypeVariableResolver.getRawClass(resolvedElementType);
    return resolved != null ? resolved : elementType;
  }

  /**
   * 해석된 요소 타입의 TypeVariable 바인딩을 반환한다.
   *
   * <p>
   * 중첩된 제네릭 타입 처리에 필요하다.
   * 예를 들어 {@code Choice<CitedMessage>}에서 {@code {T -> CitedMessage}} 바인딩을 추출한다.
   *
   * @return TypeVariable 이름 -> Type 매핑, 바인딩이 없으면 빈 맵
   */
  public java.util.Map<String, Type> getElementTypeBindings() {
    if (resolvedElementType == null) {
      return java.util.Map.of();
    }
    return TypeVariableResolver.extractBindingsFromType(resolvedElementType);
  }

  /**
   * primitive 타입을 wrapper 타입으로 변환한다.
   * LambdaMetafactory에서 boxing/unboxing을 지원하려면
   * instantiatedMethodType에 wrapper 타입을 명시해야 한다.
   *
   * @param type 변환할 타입
   * @return wrapper 타입, primitive가 아니면 원본 반환
   */
  private static Class<?> box(final Class<?> type) {
    if (!type.isPrimitive()) {
      return type;
    }
    if (type == int.class) return Integer.class;
    if (type == long.class) return Long.class;
    if (type == double.class) return Double.class;
    if (type == float.class) return Float.class;
    if (type == boolean.class) return Boolean.class;
    if (type == byte.class) return Byte.class;
    if (type == short.class) return Short.class;
    if (type == char.class) return Character.class;
    if (type == void.class) return Void.class;
    return type;
  }
}
