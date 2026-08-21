package dev.dediren.plugins.asciirender;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.contracts.render.TextRenderCharset;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.RenderEngine;
import dev.dediren.ir.LaidOutScene;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link RenderEngine} that emits text artifacts from a laid-out view, drawing every
 * group, edge, and node onto a {@link CharCanvas} and reading the result back out as one {@code
 * text} artifact. The render-policy's {@code page}, {@code margin}, {@code style}, {@code
 * accessibility}, and {@code semantic_profile} fields carry no meaning for a character grid and are
 * intentionally unused.
 */
public final class AsciiRenderEngine implements RenderEngine {
  @Override
  public String id() {
    return "ascii";
  }

  @Override
  public EngineResult<RenderResult> render(
      LaidOutScene scene, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException {
    RenderPolicy renderPolicy;
    try {
      renderPolicy = JsonSupport.objectMapper().treeToValue(policy, RenderPolicy.class);
    } catch (JacksonException error) {
      throw EngineException.semanticFailure(
          DiagnosticCode.ASCII_POLICY_INVALID.code(), error.getMessage(), "policy");
    }

    GlyphSet glyphs = charsetOf(renderPolicy);
    List<Diagnostic> diagnostics = new ArrayList<>();
    String content;
    if (scene.nodes().isEmpty() && scene.groups().isEmpty() && scene.edges().isEmpty()) {
      content = "";
    } else {
      CoordinateGrid grid = CoordinateGrid.of(scene);
      CharCanvas canvas = new CharCanvas(grid.width(), grid.height());
      for (var group : scene.groups()) {
        diagnostics.addAll(GroupBox.draw(canvas, grid, glyphs, group));
      }
      for (var edge : scene.edges()) {
        diagnostics.addAll(EdgeTracer.draw(canvas, grid, glyphs, edge));
      }
      for (var node : scene.nodes()) {
        diagnostics.addAll(NodeBox.draw(canvas, grid, glyphs, node));
      }
      for (var edge : scene.edges()) {
        diagnostics.addAll(EdgeLabelPlacer.place(canvas, grid, edge));
      }
      content = canvas.emit(glyphs);
    }

    if (isSequenceView(metadataOrNull)) {
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.ASCII_SEQUENCE_VIEW_GENERIC.code(),
              DiagnosticSeverity.WARNING,
              "render metadata declares a UML sequence view, which the ASCII render engine draws"
                  + " as generic boxes and wires rather than lifelines and messages",
              "$"));
    }

    List<RenderArtifact> artifacts = List.of(new RenderArtifact("ascii+text", content));
    return new EngineResult<>(
        new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts), diagnostics);
  }

  private static GlyphSet charsetOf(RenderPolicy policy) {
    if (policy.text() == null || policy.text().charset() == null) {
      return GlyphSet.UNICODE;
    }
    return policy.text().charset() == TextRenderCharset.ASCII ? GlyphSet.ASCII : GlyphSet.UNICODE;
  }

  /**
   * Reimplementation of {@code UmlSequenceRenderer.isSequence} local to this engine: the render
   * plugin's implementation may not be imported across engines (ArchUnit-pinned), so this mirrors
   * its ~8-line check over the same {@link RenderMetadata} shape.
   */
  private static boolean isSequenceView(RenderMetadata metadata) {
    if (metadata == null || !"uml".equals(metadata.semanticProfile())) {
      return false;
    }
    boolean hasLifeline =
        metadata.nodes().values().stream().anyMatch(selector -> "Lifeline".equals(selector.type()));
    boolean hasMessage =
        metadata.edges().values().stream().anyMatch(selector -> "Message".equals(selector.type()));
    return hasLifeline || hasMessage;
  }
}
