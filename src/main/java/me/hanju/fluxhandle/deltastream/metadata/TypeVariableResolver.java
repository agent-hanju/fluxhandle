package me.hanju.fluxhandle.deltastream.metadata;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * TypeVariable을 실제 타입으로 해석하는 유틸리티.
 *
 * <p>
 * 자식 클래스에서 부모 클래스의 TypeVariable 바인딩을 추출하고,
 * 이를 사용하여 제네릭 필드의 실제 타입을 해석한다.
 *
 * <h2>사용 예</h2>
 * <pre>{@code
 * // 부모 클래스
 * class BaseResponse<T> { List<Choice<T>> choices; }
 *
 * // 자식 클래스
 * class CitedResponse extends BaseResponse<CitedMessage> {}
 *
 * // 바인딩 추출
 * Map<String, Type> bindings = TypeVariableResolver.buildBindings(CitedResponse.class);
 * // 결과: {T -> CitedMessage}
 *
 * // TypeVariable 해석
 * Type resolved = TypeVariableResolver.resolveType(fieldGenericType, bindings);
 * // List<Choice<T>> -> List<Choice<CitedMessage>>
 * }</pre>
 */
public final class TypeVariableResolver {

  private TypeVariableResolver() {
  }

  /**
   * 주어진 타입의 TypeVariable 바인딩 맵을 구축한다.
   *
   * <p>
   * 상속 계층을 따라 올라가며 모든 TypeVariable 바인딩을 수집한다.
   * 예를 들어 {@code CitedResponse extends BaseResponse<CitedMessage>}에서
   * {@code {T -> CitedMessage}} 바인딩을 추출한다.
   *
   * @param concreteType 분석할 구체적인 타입 (예: CitedResponse.class)
   * @return TypeVariable 이름 -> 실제 Type 매핑 (불변 맵)
   */
  public static Map<String, Type> buildBindings(final Class<?> concreteType) {
    if (concreteType == null) {
      return Map.of();
    }

    final Map<String, Type> bindings = new HashMap<>();
    Class<?> current = concreteType;

    while (current != null && current != Object.class) {
      final Type genericSuper = current.getGenericSuperclass();

      if (genericSuper instanceof ParameterizedType pt) {
        final Class<?> rawSuper = (Class<?>) pt.getRawType();
        final TypeVariable<?>[] typeParams = rawSuper.getTypeParameters();
        final Type[] actualArgs = pt.getActualTypeArguments();

        for (int i = 0; i < typeParams.length && i < actualArgs.length; i++) {
          final String varName = typeParams[i].getName();
          Type actualType = actualArgs[i];

          // TypeVariable이 또 다른 TypeVariable을 참조하면 이미 해석된 값으로 치환
          if (actualType instanceof TypeVariable<?> tv) {
            final Type resolved = bindings.get(tv.getName());
            if (resolved != null) {
              actualType = resolved;
            }
          }

          bindings.put(varName, actualType);
        }
      }

      current = current.getSuperclass();
    }

    return Map.copyOf(bindings);
  }

  /**
   * 바인딩을 사용하여 Type을 해석한다.
   *
   * <p>
   * TypeVariable은 바인딩된 실제 타입으로 치환된다.
   * ParameterizedType의 타입 인자도 재귀적으로 해석된다.
   *
   * @param type     해석할 타입
   * @param bindings TypeVariable 바인딩 맵
   * @return 해석된 타입 (변경이 없으면 원본 타입 반환)
   */
  public static Type resolveType(final Type type, final Map<String, Type> bindings) {
    if (type == null || bindings == null || bindings.isEmpty()) {
      return type;
    }

    // TypeVariable: 바인딩에서 찾아서 치환
    if (type instanceof TypeVariable<?> tv) {
      final Type resolved = bindings.get(tv.getName());
      return resolved != null ? resolved : type;
    }

    // ParameterizedType: 타입 인자를 재귀적으로 해석
    if (type instanceof ParameterizedType pt) {
      final Type[] actualArgs = pt.getActualTypeArguments();
      final Type[] resolvedArgs = new Type[actualArgs.length];
      boolean changed = false;

      for (int i = 0; i < actualArgs.length; i++) {
        resolvedArgs[i] = resolveType(actualArgs[i], bindings);
        if (resolvedArgs[i] != actualArgs[i]) {
          changed = true;
        }
      }

      if (changed) {
        return new ResolvedParameterizedType(pt.getRawType(), resolvedArgs, pt.getOwnerType());
      }
    }

    return type;
  }

  /**
   * Type에서 raw Class를 추출한다.
   *
   * @param type 추출할 타입
   * @return raw Class, 추출 불가시 null
   */
  public static Class<?> getRawClass(final Type type) {
    if (type instanceof Class<?> cls) {
      return cls;
    }
    if (type instanceof ParameterizedType pt) {
      final Type rawType = pt.getRawType();
      if (rawType instanceof Class<?> cls) {
        return cls;
      }
    }
    return null;
  }

  /**
   * ParameterizedType에서 TypeVariable 바인딩을 추출한다.
   *
   * <p>
   * 예를 들어 {@code Choice<CitedMessage>}에서 {@code {T -> CitedMessage}}를 추출한다.
   *
   * @param type 추출할 ParameterizedType
   * @return TypeVariable 이름 -> Type 매핑 (불변 맵)
   */
  public static Map<String, Type> extractBindingsFromType(final Type type) {
    if (!(type instanceof ParameterizedType pt)) {
      return Map.of();
    }

    final Class<?> rawType = getRawClass(pt);
    if (rawType == null) {
      return Map.of();
    }

    final TypeVariable<?>[] typeParams = rawType.getTypeParameters();
    final Type[] actualArgs = pt.getActualTypeArguments();

    if (typeParams.length == 0 || actualArgs.length == 0) {
      return Map.of();
    }

    final Map<String, Type> bindings = new HashMap<>();
    for (int i = 0; i < typeParams.length && i < actualArgs.length; i++) {
      bindings.put(typeParams[i].getName(), actualArgs[i]);
    }

    return Map.copyOf(bindings);
  }

  /**
   * 해석된 ParameterizedType을 나타내는 내부 클래스.
   *
   * <p>
   * TypeVariable이 실제 타입으로 치환된 후의 ParameterizedType을 표현한다.
   */
  static final class ResolvedParameterizedType implements ParameterizedType {

    private final Type rawType;
    private final Type[] actualTypeArguments;
    private final Type ownerType;

    ResolvedParameterizedType(
        final Type rawType,
        final Type[] actualTypeArguments,
        final Type ownerType) {
      this.rawType = rawType;
      this.actualTypeArguments = actualTypeArguments.clone();
      this.ownerType = ownerType;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return actualTypeArguments.clone();
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ParameterizedType other)) {
        return false;
      }
      return Objects.equals(rawType, other.getRawType())
          && java.util.Arrays.equals(actualTypeArguments, other.getActualTypeArguments())
          && Objects.equals(ownerType, other.getOwnerType());
    }

    @Override
    public int hashCode() {
      return Objects.hash(rawType, java.util.Arrays.hashCode(actualTypeArguments), ownerType);
    }

    @Override
    public String toString() {
      final StringBuilder sb = new StringBuilder();
      sb.append(rawType instanceof Class<?> cls ? cls.getName() : rawType.toString());
      if (actualTypeArguments.length > 0) {
        sb.append('<');
        for (int i = 0; i < actualTypeArguments.length; i++) {
          if (i > 0) {
            sb.append(", ");
          }
          sb.append(actualTypeArguments[i].getTypeName());
        }
        sb.append('>');
      }
      return sb.toString();
    }
  }
}
