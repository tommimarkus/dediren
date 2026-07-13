package dev.dediren.contracts.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

  public static <T> Map<String, T> mapOrEmpty(Map<String, T> values) {
    return values == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(values));
  }
}
