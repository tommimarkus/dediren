package dev.dediren.contracts.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractCollectionsTest {
  @Test
  void mapOrEmptyPreservesInsertionOrder() {
    Map<String, String> source = new LinkedHashMap<>();
    // Enough keys that hash-probe order reliably diverges from insertion order.
    for (String key : new String[] {"zeta", "alpha", "mu", "beta", "omega", "kappa", "iota"}) {
      source.put(key, key.toUpperCase(java.util.Locale.ROOT));
    }
    assertThat(ContractCollections.mapOrEmpty(source).keySet())
        .containsExactly("zeta", "alpha", "mu", "beta", "omega", "kappa", "iota");
  }

  @Test
  void mapOrEmptyStaysImmutableAndNullHostile() {
    Map<String, String> copy = ContractCollections.mapOrEmpty(Map.of("a", "b"));
    assertThrows(UnsupportedOperationException.class, () -> copy.put("c", "d"));
    Map<String, String> withNull = new LinkedHashMap<>();
    withNull.put("a", null);
    assertThrows(NullPointerException.class, () -> ContractCollections.mapOrEmpty(withNull));
    // LinkedHashMap permits a null key (unlike Map.of), so this must be pinned separately from
    // the null-value case above.
    Map<String, String> withNullKey = new LinkedHashMap<>();
    withNullKey.put(null, "v");
    assertThrows(NullPointerException.class, () -> ContractCollections.mapOrEmpty(withNullKey));
    assertThat(ContractCollections.mapOrEmpty(null)).isEmpty();
  }
}
