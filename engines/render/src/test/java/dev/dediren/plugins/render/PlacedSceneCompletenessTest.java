package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Guards the one seam the sealed {@code PlacedElement} hierarchy does <em>not</em> cover.
 *
 * <p>Making a new drawable kind implement {@link PlacedElement} is a compile error until it states
 * its {@code contributeBounds}, so a whole kind cannot be silently missing from the fold. But a
 * scene's {@code elements()} method and its emitter's loop set are two separately hand-maintained
 * enumerations over the same record's fields: adding a {@code List<PlacedX>} component, wiring it
 * into the emitter, and forgetting {@code elements()} compiles and passes every existing test,
 * while the new drawable silently stops contributing to the viewBox.
 *
 * <p>That is the same failure this refactor removed at pass scale — two enumerations kept in step
 * by inspection — recurring at record scale. So this test does not name the fields: it reflects
 * over the record's components, so a component added tomorrow is covered by the check today.
 */
class PlacedSceneCompletenessTest {

  @Test
  void placedSceneElementsCoverEveryDrawableList() throws Exception {
    PlacedScene scene =
        SvgDocument.resolve(
            fixture("fixtures/layout-result/pipeline-rich.json", LayoutResult.class),
            null,
            fixture("fixtures/render-policy/default-svg.json", RenderPolicy.class));

    assertCoversEveryDrawableList(scene, scene.elements());
  }

  @Test
  void placedSequenceSceneElementsCoverEveryDrawableList() throws Exception {
    // Several fixtures, because no single one populates all eight drawable kinds — and a kind left
    // empty is a kind this check cannot see. The union assertion at the end is what turns "empty
    // here" from a silent skip into a named failure.
    List<PlacedSequenceScene> scenes =
        List.of(
            sequenceScene("uml-sequence-fragments"),
            sequenceScene("uml-sequence-lifecycle"),
            sequenceScene("uml-sequence-self-message"),
            // No committed fixture contains a Gate node at all — "Gate" appears in the corpus only
            // as a style-override key — so this scene injects one. Without it `gates` is empty
            // everywhere and the union assertion below would be satisfied by a hole rather than by
            // coverage. See SequenceBoundsCompletenessTest, which found the same gap from the
            // bounds side.
            sequenceSceneWithGate());

    List<String> exercised = new ArrayList<>();
    for (PlacedSequenceScene scene : scenes) {
      exercised.addAll(assertCoversEveryDrawableList(scene, scene.elements()));
    }

    assertThat(exercised)
        .as(
            "every drawable list must be non-empty in at least one fixture, or elements() could"
                + " omit it and this test would not notice")
        .containsAll(drawableComponentNames(PlacedSequenceScene.class));
  }

  private static PlacedSequenceScene sequenceScene(String name) throws Exception {
    return new UmlSequenceRenderer(
            fixture("fixtures/layout-result/" + name + ".json", LayoutResult.class),
            fixture("fixtures/render-metadata/" + name + ".json", RenderMetadata.class),
            fixture("fixtures/render-policy/uml-svg.json", RenderPolicy.class))
        .resolve();
  }

  /** The basic sequence fixture with a Gate node grafted on, so {@code gates} is non-empty. */
  private static PlacedSequenceScene sequenceSceneWithGate() throws Exception {
    ObjectNode layout =
        (ObjectNode)
            RenderTestSupport.fixtureJson("fixtures/layout-result/uml-sequence-basic.json");
    ObjectNode gate = layout.withArray("nodes").addObject();
    gate.put("id", "gate-out").put("source_id", "gate-out").put("projection_id", "gate-out");
    gate.put("x", 559.0).put("y", 330.0).put("width", 6.0).put("height", 6.0).put("label", "");

    ObjectNode metadata =
        (ObjectNode)
            RenderTestSupport.fixtureJson("fixtures/render-metadata/uml-sequence-basic.json");
    ObjectNode selector = ((ObjectNode) metadata.get("nodes")).putObject("gate-out");
    selector.put("type", "Gate").put("source_id", "gate-out");
    selector.putObject("properties").put("interaction", "interaction-place-order");

    var mapper = JsonSupport.objectMapper();
    return new UmlSequenceRenderer(
            mapper.treeToValue(layout, LayoutResult.class),
            mapper.treeToValue(metadata, RenderMetadata.class),
            fixture("fixtures/render-policy/uml-svg.json", RenderPolicy.class))
        .resolve();
  }

  /** Component names whose declared element type is a drawable, populated or not. */
  private static List<String> drawableComponentNames(Class<?> type) {
    List<String> names = new ArrayList<>();
    for (RecordComponent component : type.getRecordComponents()) {
      if (!List.class.isAssignableFrom(component.getType())) {
        continue;
      }
      String generic = component.getGenericType().getTypeName();
      if (generic.contains("Placed")) {
        names.add(component.getName());
      }
    }
    return names;
  }

  /**
   * Every record component that holds drawables must be represented in {@code elements()}, by
   * identity. Identity rather than count so a component that is accidentally added twice, or a
   * different list added in place of the missing one, still fails.
   */
  private static List<String> assertCoversEveryDrawableList(
      Record scene, List<PlacedElement> elements) {
    List<PlacedElement> expected = new ArrayList<>();
    List<String> populated = new ArrayList<>();
    for (String name : drawableComponentNames(scene.getClass())) {
      List<?> items = (List<?>) readComponent(scene, name);
      if (!items.isEmpty()) {
        populated.add(name);
      }
      items.forEach(item -> expected.add((PlacedElement) item));
    }

    assertThat(populated)
        .as("%s must populate at least one drawable list", scene.getClass().getSimpleName())
        .isNotEmpty();

    assertThat(elements)
        .as(
            "elements() must contain exactly the drawables held in %s — a component wired into the"
                + " emitter but not into elements() stops contributing to the viewBox",
            scene.getClass().getSimpleName())
        .containsExactlyInAnyOrderElementsOf(expected);

    return populated;
  }

  private static Object readComponent(Record scene, String name) {
    for (RecordComponent component : scene.getClass().getRecordComponents()) {
      if (component.getName().equals(name)) {
        try {
          return component.getAccessor().invoke(scene);
        } catch (ReflectiveOperationException error) {
          throw new AssertionError("could not read record component " + name, error);
        }
      }
    }
    throw new AssertionError("no such record component: " + name);
  }

  private static <T> T fixture(String path, Class<T> type) throws Exception {
    return JsonSupport.objectMapper().treeToValue(RenderTestSupport.fixtureJson(path), type);
  }
}
