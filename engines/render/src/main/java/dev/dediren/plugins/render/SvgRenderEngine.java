package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.SvgDocument.renderSvg;

import dev.dediren.archimate.ArchimateTypeValidationException;
import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderArtifact;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.RenderEngine;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.plugins.render.svg.Geometry;
import dev.dediren.plugins.render.svg.LabelBox;
import dev.dediren.uml.UmlValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link RenderEngine} that emits SVG artifacts from a laid-out view. Extracted from
 * {@code Main}'s render orchestration ({@link RenderInputValidator}, style resolution, {@code
 * SvgDocument} emission), preserving every render diagnostic code and exit code. The render-policy
 * arrives as a raw {@link JsonNode} (the render plugin owns its policy validation), and this engine
 * deserializes it into the typed {@link RenderPolicy} the same way the process path did at parse.
 */
public final class SvgRenderEngine implements RenderEngine {

  /**
   * The metadata profiles that carry notation semantics. {@code generic-graph} is deliberately
   * absent: it has no notation to drop, so a policy without {@code semantic_profile} renders it
   * exactly as intended.
   */
  private static final Set<String> NOTATION_PROFILES = Set.of("archimate", "uml");

  @Override
  public String id() {
    return "render";
  }

  /**
   * Converts render-input bytes to the typed record the engine consumes. render publishes no
   * dedicated parse-failure envelope, so a malformed stream surfaces as today's raw (non-enveloped)
   * failure by letting the underlying parse exception propagate.
   */
  public ParsedInput parseInput(byte[] input) {
    return JsonSupport.objectMapper().readValue(input, ParsedInput.class);
  }

  @Override
  public EngineResult<RenderResult> render(
      LaidOutScene scene, JsonNode policy, RenderMetadata metadataOrNull) throws EngineException {
    LayoutResult layout = LaidOutSceneMapper.toResult(scene);
    RenderPolicy renderPolicy = JsonSupport.objectMapper().treeToValue(policy, RenderPolicy.class);
    try {
      RenderInputValidator.validate(layout, metadataOrNull, renderPolicy);
    } catch (RenderInputValidator.PolicyValidationException error) {
      throw failure(DiagnosticCode.SVG_POLICY_INVALID.code(), error.getMessage(), error.path());
    } catch (RenderInputValidator.RenderMetadataUsageException error) {
      throw failure(error.code(), error.getMessage(), error.path());
    } catch (ArchimateTypeValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    } catch (UmlValidationException error) {
      throw failure(error.code(), error.message(), error.path());
    }

    String svg = renderSvg(layout, metadataOrNull, renderPolicy);
    List<RenderArtifact> artifacts = List.of(new RenderArtifact("svg", svg));
    List<Diagnostic> diagnostics = new ArrayList<>();
    diagnostics.addAll(notationProfileNotAppliedDiagnostics(metadataOrNull, renderPolicy));
    diagnostics.addAll(edgeLabelOccludedDiagnostics(layout, metadataOrNull, renderPolicy));
    return new EngineResult<>(
        new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts), diagnostics);
  }

  /**
   * Warns when the metadata carries a notation profile the policy never declares. Nothing is
   * invalid — the render succeeds and the SVG is well formed — but every notation-specific shape,
   * decorator, and label placement is dropped, while layout already sized the notation's symbol
   * nodes (a UML {@code DecisionNode} or {@code Port} is a fixed ~32px glyph whose label belongs
   * outside it). Painted generically, that geometry puts an in-shape label over a symbol far too
   * small to hold it, so the agent needs to know its profile went unused. A warning rather than an
   * error: the combination is legal, and for a graph without symbol nodes a generic render of a
   * notation view is a reasonable thing to ask for.
   */
  private static List<Diagnostic> notationProfileNotAppliedDiagnostics(
      RenderMetadata metadataOrNull, RenderPolicy policy) {
    if (metadataOrNull == null
        || policy.semanticProfile() != null
        || !NOTATION_PROFILES.contains(metadataOrNull.semanticProfile())) {
      return List.of();
    }
    return List.of(
        new Diagnostic(
            DiagnosticCode.RENDER_METADATA_PROFILE_NOT_APPLIED.code(),
            DiagnosticSeverity.WARNING,
            "render metadata declares semantic_profile "
                + metadataOrNull.semanticProfile()
                + " but the render policy declares none, so "
                + metadataOrNull.semanticProfile()
                + " shapes, decorators and label placement are not applied; add"
                + " semantic_profile to the render policy to render the notation",
            "policy.semantic_profile"));
  }

  /**
   * Warns when a placed edge label's visible box still overlaps a node rect after placement. SVG
   * paint order is group, then edge, then node, so a label left in that state is painted underneath
   * the node and disappears — the sibling fallback in {@code EdgeRenderer} already picks the
   * least-overlapping candidate it can find, but a crowded view can leave every candidate
   * overlapping something. A warning rather than an error: the render still succeeds and produces
   * well-formed SVG, but the agent needs to know a label is effectively invisible.
   *
   * <p>Reuses {@code SvgDocument.resolve} and {@code Geometry.nodeObstacleBoxes} rather than
   * recomputing placement — see {@link PlacedScene} for why a second, independent geometry pass is
   * the bug class this package guards against. UML sequence views take a different rendering lane
   * ({@code UmlSequenceRenderer}) with no {@link PlacedScene} to inspect, so they are skipped here.
   */
  private static List<Diagnostic> edgeLabelOccludedDiagnostics(
      LayoutResult layout, RenderMetadata metadataOrNull, RenderPolicy policy) {
    if (UmlSequenceRenderer.isSequence(metadataOrNull)) {
      return List.of();
    }
    PlacedScene scene = SvgDocument.resolve(layout, metadataOrNull, policy);
    List<LabelBox> nodeBoxes = Geometry.nodeObstacleBoxes(layout);
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (PlacedScene.PlacedEdge placedEdge : scene.edges()) {
      PlacedScene.PlacedEdgeLabel label = placedEdge.label();
      if (label == null || nodeBoxes.stream().noneMatch(label.visibleBox()::overlaps)) {
        continue;
      }
      diagnostics.add(
          new Diagnostic(
              DiagnosticCode.RENDER_EDGE_LABEL_OCCLUDED.code(),
              DiagnosticSeverity.WARNING,
              "edge label \""
                  + label.text()
                  + "\" on edge "
                  + placedEdge.edge().id()
                  + " still overlaps a node after placement and may be painted underneath it,"
                  + " making the label invisible",
              "edges[" + placedEdge.edge().id() + "].label"));
    }
    return diagnostics;
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }

  /** Typed render input parsed from stdin: a laid-out view, optional metadata, and raw policy. */
  record ParsedInput(LayoutResult layoutResult, RenderMetadata renderMetadata, JsonNode policy) {}
}
