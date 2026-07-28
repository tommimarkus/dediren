package dev.dediren.contracts.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.Point;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;

/**
 * Pins the shared mapper's null-for-primitive contract. {@code dediren render}/{@code export}
 * Jackson-parse layout files with no schema validation in front, and the geometry fields ({@link
 * LaidOutNode} x/y/width/height, {@link Point} x/y) are primitive doubles — so the mapper is the
 * only gate. Jackson 3's default ({@code FAIL_ON_NULL_FOR_PRIMITIVES} enabled) must stay in force:
 * an explicit JSON {@code null} for a geometry field is a structured parse error the caller can
 * surface, never a silent {@code 0.0} placed at the origin.
 */
class JsonSupportTest {

  private static final String LAID_OUT_NODE_JSON =
      """
      {"id":"n1","source_id":"n1","projection_id":"p1","x":%s,"y":0,"width":10,"height":10,"label":"N1"}""";

  @Test
  void explicitNullForPrimitiveNodeGeometryIsAParseErrorNotZero() {
    assertThat(JsonSupport.readValue(LAID_OUT_NODE_JSON.formatted("4.5"), LaidOutNode.class).x())
        .as("the same document with a real coordinate must parse — only the null may fail")
        .isEqualTo(4.5);

    assertThatThrownBy(
            () -> JsonSupport.readValue(LAID_OUT_NODE_JSON.formatted("null"), LaidOutNode.class))
        .isInstanceOf(DatabindException.class)
        .hasMessageContaining("null");
  }

  @Test
  void explicitNullForPrimitivePointCoordinateIsAParseErrorNotZero() {
    assertThatThrownBy(() -> JsonSupport.readValue("{\"x\":1.5,\"y\":null}", Point.class))
        .isInstanceOf(DatabindException.class)
        .hasMessageContaining("null");
  }
}
