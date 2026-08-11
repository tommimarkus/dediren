package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The id minter's two contracts: it must leave well-formed ids alone (byte identity, on which every
 * render golden depends) and it must turn anything else into an identifier whose {@code url(#…)}
 * reference actually resolves back to it.
 */
class SvgIdsTest {

  /** The same token grammar a user agent applies: the url token ends at the first ')' or space. */
  private static final Pattern URL_REFERENCE = Pattern.compile("url\\(#([^)\\s]+)\\)");

  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*$");

  @ParameterizedTest
  @ValueSource(
      strings = {
        "marker-end-orders-realizes-service",
        "marker-start-order-has-lines",
        "node-fill-client",
        "group-fill-g1",
        "a",
        "A0",
        "0",
        "dotted.id_with-every.safe_char-1",
        "marker-end-",
      })
  void safeIdsMintUnchanged(String id) {
    // Byte identity. Every fixture and golden id is in this set; a minter that "tidied" them would
    // move all 16 render goldens at once.
    assertThat(new SvgIds().mint(id)).isEqualTo(id);
  }

  @ParameterizedTest
  @ValueSource(strings = {"marker-end-a)b", "marker-end-a b", "marker-end-a\"b", "marker-end-<x>"})
  void unsafeIdsMintToAResolvableIdentifier(String id) {
    SvgIds ids = new SvgIds();

    String minted = ids.mint(id);

    assertThat(minted).matches(SAFE_IDENTIFIER);
    assertThat(referencedId(ids.reference(minted)))
        .as("the url(#…) token must resolve to the whole minted id, not a prefix of it")
        .isEqualTo(minted);
  }

  @Test
  void emptyIdMintsToAnIdentifier() {
    SvgIds ids = new SvgIds();

    String minted = ids.mint("");

    assertThat(minted).matches(SAFE_IDENTIFIER);
    assertThat(referencedId(ids.reference(minted))).isEqualTo(minted);
  }

  @Test
  void unsafeCharactersAreReplacedPositionally() {
    SvgIds ids = new SvgIds();

    // Pinned rather than merely "safe": the mapping is what makes output stable across runs.
    assertThat(ids.mint("marker-end-a)b")).isEqualTo("marker-end-a_b");
    assertThat(ids.mint("-leading")).isEqualTo("_leading");
    assertThat(ids.mint("注文")).isEqualTo("__");
  }

  @Test
  void duplicateIdsMintToDistinctResults() {
    SvgIds ids = new SvgIds();

    List<String> minted =
        List.of(ids.mint("marker-end-dup"), ids.mint("marker-end-dup"), ids.mint("marker-end-dup"));

    assertThat(minted)
        .containsExactly("marker-end-dup", "marker-end-dup-2", "marker-end-dup-3")
        .doesNotHaveDuplicates();
  }

  @Test
  void collidingUnsafeIdsAlsoMintToDistinctResults() {
    SvgIds ids = new SvgIds();

    // Two different broken ids that sanitize to the same thing must still not collide.
    assertThat(ids.mint("marker-end-a)b")).isEqualTo("marker-end-a_b");
    assertThat(ids.mint("marker-end-a b")).isEqualTo("marker-end-a_b-2");
  }

  @Test
  void mintingIsDeterministicAcrossDocuments() {
    List<String> first = mintSequence();
    List<String> second = mintSequence();

    assertThat(first).isEqualTo(second);
  }

  @Test
  void aFreshDocumentStartsFromAnEmptyIdSet() {
    assertThat(new SvgIds().mint("marker-end-dup")).isEqualTo("marker-end-dup");
    assertThat(new SvgIds().mint("marker-end-dup")).isEqualTo("marker-end-dup");
  }

  @Test
  void referenceIsNullForAnAbsentMarker() {
    assertThat(new SvgIds().reference(null)).isNull();
  }

  @Test
  void referenceRejectsAnIdThisDocumentNeverMinted() {
    SvgIds ids = new SvgIds();
    ids.mint("marker-end-dup");

    // The raw layout id, not the minted one: exactly the miswiring that silently drops a marker.
    assertThatThrownBy(() -> ids.reference("marker-end-a)b"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("never minted");
  }

  private static List<String> mintSequence() {
    SvgIds ids = new SvgIds();
    List<String> minted = new ArrayList<>();
    for (String candidate : List.of("dup", "dup", "a)b", "a b", "a\"b", "<x>", "", "dup")) {
      minted.add(ids.mint(candidate));
    }
    return minted;
  }

  private static String referencedId(String reference) {
    Matcher matcher = URL_REFERENCE.matcher(reference);
    return matcher.find() ? matcher.group(1) : null;
  }
}
