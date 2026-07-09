package dev.dediren.ir;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourcePointersTest {
  @Test
  void nodePointerTargetsSourceNodesByIndex() {
    assertThat(SourcePointers.node(3).value()).isEqualTo("/nodes/3");
  }

  @Test
  void relationshipPointerTargetsSourceRelationshipsByIndex() {
    assertThat(SourcePointers.relationship(0).value()).isEqualTo("/relationships/0");
  }
}
