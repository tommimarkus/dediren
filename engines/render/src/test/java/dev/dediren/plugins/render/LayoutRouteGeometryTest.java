package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.ir.LaidOutSceneMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class LayoutRouteGeometryTest {

  @Test
  void malformedCubicRouteReturnsAStructuredInputDiagnostic() throws Exception {
    assertRouteFailure(
        """
        {
          "kind": "cubic_bezier",
          "start": {"x": 0.0, "y": 0.0},
          "segments": [{
            "control1": null,
            "control2": {"x": 20.0, "y": 10.0},
            "end": {"x": 30.0, "y": 0.0}
          }]
        }
        """,
        "DEDIREN_LAYOUT_NON_FINITE_GEOMETRY",
        "$.edges[0].route.segments[0].control1");
  }

  @Test
  void onePointAndAllCoincidentRoutesReturnStructuredDegenerateDiagnostics() throws Exception {
    assertRouteFailure(
        """
        {"kind": "polyline", "points": [{"x": 4.0, "y": 5.0}]}
        """,
        "DEDIREN_LAYOUT_ROUTE_POINTS_INSUFFICIENT",
        "$.edges[0].route.points");
    assertRouteFailure(
        """
        {
          "kind": "cubic_bezier",
          "start": {"x": 4.0, "y": 5.0},
          "segments": [{
            "control1": {"x": 4.0, "y": 5.0},
            "control2": {"x": 4.0, "y": 5.0},
            "end": {"x": 4.0, "y": 5.0}
          }]
        }
        """,
        "DEDIREN_LAYOUT_ROUTE_POINTS_INSUFFICIENT",
        "$.edges[0].route");
  }

  private static void assertRouteFailure(String route, String code, String path) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set(
        "layout_result",
        JsonSupport.objectMapper()
            .readTree(
                """
                {
                  "layout_result_schema_version": "layout-result.schema.v3",
                  "view_id": "bad-route",
                  "nodes": [],
                  "edges": [{
                    "id": "route", "source": "a", "target": "b",
                    "source_id": "route", "projection_id": "route", "routing_hints": [],
                    "route": %s,
                    "label": "route"
                  }],
                  "groups": [],
                  "warnings": []
                }
                """
                    .formatted(route)));
    input.set(
        "policy",
        JsonSupport.objectMapper()
            .readTree(
                Files.readString(
                    workspaceRoot().resolve("fixtures/render-policy/default-svg.json"))));
    SvgRenderEngine engine = new SvgRenderEngine();
    SvgRenderEngine.ParsedInput parsed =
        engine.parseInput(JsonSupport.objectMapper().writeValueAsBytes(input));

    assertThatThrownBy(
            () ->
                engine.render(
                    LaidOutSceneMapper.toScene(parsed.layoutResult()),
                    parsed.policy(),
                    parsed.renderMetadata()))
        .isInstanceOf(EngineException.class)
        .satisfies(
            failure -> {
              EngineException error = (EngineException) failure;
              assertThat(error.exitCode()).isEqualTo(2);
              assertThat(error.diagnostics().getFirst().code()).isEqualTo(code);
              assertThat(error.diagnostics().getFirst().path()).isEqualTo(path);
            });
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}
