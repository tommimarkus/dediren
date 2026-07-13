package dev.dediren.contracts.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ContractCollections {
  private ContractCollections() {}

  public static <T> List<T> listOrEmpty(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  /**
   * An immutable copy that preserves {@code null} (unlike {@link #listOrEmpty}), for optional list
   * fields where null and empty differ — e.g. a render-policy override that is "unset" (inherit)
   * versus an explicit empty list.
   */
  public static <T> List<T> copyOrNull(List<T> values) {
    return values == null ? null : List.copyOf(values);
  }

  /**
   * An immutable, null-hostile copy that preserves insertion order (unlike {@link Map#copyOf},
   * which returns a hash-ordered map). Map-carrying contract records serialize their map fields in
   * this order, so preserving it keeps output byte-stable and matches author intent.
   */
  public static <T> Map<String, T> mapOrEmpty(Map<String, T> values) {
    if (values == null) {
      return Map.of();
    }
    LinkedHashMap<String, T> copy = new LinkedHashMap<>(values);
    copy.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "map key");
          Objects.requireNonNull(value, "map value");
        });
    return Collections.unmodifiableMap(copy);
  }
}
