package dev.dediren.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuideCatalogTest {

  @Test
  void indexListsEveryTopic() {
    String index = GuideCatalog.index();

    assertThat(GuideCatalog.topics()).isNotEmpty();
    for (String topic : GuideCatalog.topics()) {
      assertThat(index).contains(topic);
    }
  }

  @Test
  void sectionReturnsTheRequestedSection() {
    String section = GuideCatalog.section("render-policy");

    assertThat(section).contains("Render Policy Options");
    assertThat(section).doesNotContain("## Repair Rules");
  }

  @Test
  void unknownTopicListsTheValidTopics() {
    String section = GuideCatalog.section("no-such-topic");

    assertThat(section).contains("unknown topic 'no-such-topic'");
    assertThat(section).contains("render-policy");
  }

  @Test
  void everyTopicResolvesToARealHeading() {
    List<String> headings = GuideCatalog.headings();

    for (String topic : GuideCatalog.topics()) {
      assertThat(GuideCatalog.section(topic))
          .as("topic '%s' must resolve to a real section", topic)
          .doesNotContain("unknown topic");
    }
    assertThat(headings).isNotEmpty();
  }

  @Test
  void everyHeadingIsReachableFromSomeTopic() {
    List<String> covered = GuideCatalog.topics().stream().map(GuideCatalog::headingFor).toList();

    assertThat(covered)
        .as(
            "every ## heading in docs/agent-usage.md must be reachable from at least one guide"
                + " topic — add a topic to GuideCatalog.TOPICS when you add a section")
        .containsAll(GuideCatalog.headings());
  }

  @Test
  void hasSectionAgreesWithWhatSectionActuallyReturns() {
    for (String topic : GuideCatalog.topics()) {
      assertThat(GuideCatalog.hasSection(topic))
          .as("topic '%s' resolves to a real section, so hasSection must say so", topic)
          .isTrue();
    }
    assertThat(GuideCatalog.hasSection("no-such-topic")).isFalse();
  }
}
