package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.svg.SvgDocument.renderSvg;

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
import dev.dediren.uml.UmlValidationException;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * First-party {@link RenderEngine} that emits SVG artifacts from a laid-out view. Extracted from
 * {@code Main}'s render orchestration ({@link RenderInputValidator}, style resolution, {@code
 * SvgDocument} emission), preserving every render diagnostic code and exit code. The render-policy
 * arrives as a raw {@link JsonNode} (the render plugin owns its policy validation), and this engine
 * deserializes it into the typed {@link RenderPolicy} the same way the process path did at parse.
 */
public final class SvgRenderEngine implements RenderEngine {

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
    return new EngineResult<>(
        new RenderResult(ContractVersions.RENDER_RESULT_SCHEMA_VERSION, artifacts), List.of());
  }

  private static EngineException failure(String code, String message, String path) {
    return new EngineException(
        List.of(new Diagnostic(code, DiagnosticSeverity.ERROR, message, path)), 3);
  }

  /** Typed render input parsed from stdin: a laid-out view, optional metadata, and raw policy. */
  record ParsedInput(LayoutResult layoutResult, RenderMetadata renderMetadata, JsonNode policy) {}
}
