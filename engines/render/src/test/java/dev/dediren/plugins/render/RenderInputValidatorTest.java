package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.render.RenderMetadata;
import dev.dediren.contracts.render.RenderPolicy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Dedicated negative-partition coverage for {@link RenderInputValidator}, the ~660-line front door
 * that rejects malformed render-policy and render-metadata inputs before any SVG is emitted. Its
 * rejection branches were previously exercised only incidentally through the full render boundary,
 * so most of its error paths went uncovered. Each test here feeds the minimal bad input straight to
 * {@link RenderInputValidator#validate} and asserts the exact JSON pointer the validator emits —
 * the property that lets an agent decide, from the published envelope alone, what to fix.
 *
 * <p>Two exception families cross this seam, and they carry different signals:
 *
 * <ul>
 *   <li>{@link RenderInputValidator.PolicyValidationException} carries a JSON {@code path} only.
 *       The {@link SvgRenderEngine} maps <em>every</em> one of these to a single published code,
 *       {@code DEDIREN_SVG_POLICY_INVALID} (that mapping is covered at the engine level by {@code
 *       RequiredPolicyFieldsTest}, {@code GenericShapePolicyTest}, and {@code ColorGrammarTest}),
 *       so the exception type <em>is</em> the code and the {@code path} is the discriminator
 *       between branches. These tests therefore assert the precise path.
 *   <li>{@link RenderInputValidator.RenderMetadataUsageException} carries both a {@code code} and a
 *       {@code path}; the engine republishes the code verbatim. These tests assert both.
 * </ul>
 */
class RenderInputValidatorTest {

  private static final LayoutResult LAYOUT = loadBasicLayout();

  // --- PolicyValidationException branches: assert the discriminating JSON pointer -------------

  @Test
  void invalidColorIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("node").put("fill", "notacolor#");

    assertThat(policyRejection(policy).path()).isEqualTo("style.node.fill");
  }

  @Test
  void numberAboveItsMaximumIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("node").put("stroke_width", 999.0);

    assertThat(policyRejection(policy).path()).isEqualTo("style.node.stroke_width");
  }

  @Test
  void fontSizeAtTheExclusiveMinimumIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("font").put("size", 0.0);

    assertThat(policyRejection(policy).path()).isEqualTo("style.font.size");
  }

  @Test
  void emptyFontFamilyIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("font").put("family", "");

    assertThat(policyRejection(policy).path()).isEqualTo("style.font.family");
  }

  @Test
  void emptyDashPatternIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("edge").putArray("dash_pattern"); // empty array

    assertThat(policyRejection(policy).path()).isEqualTo("style.edge.dash_pattern");
  }

  @Test
  void nonPositiveDashEntryIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("node").putArray("dash_pattern").add(0.0);

    assertThat(policyRejection(policy).path()).isEqualTo("style.node.dash_pattern");
  }

  @Test
  void gradientWithoutTypeIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    ObjectNode gradient = policy.putObject("style").putObject("node").putObject("fill_gradient");
    gradient.putArray("stops").addObject().put("offset", 0).put("color", "#000000");

    assertThat(policyRejection(policy).path()).isEqualTo("style.node.fill_gradient.type");
  }

  @Test
  void gradientWithNoStopsIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    ObjectNode gradient = policy.putObject("style").putObject("node").putObject("fill_gradient");
    gradient.put("type", "linear").putArray("stops"); // empty stops

    assertThat(policyRejection(policy).path()).isEqualTo("style.node.fill_gradient.stops");
  }

  @Test
  void gradientStopOffsetOutOfRangeIsRejectedAtItsIndexedPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    ObjectNode gradient = policy.putObject("style").putObject("node").putObject("fill_gradient");
    gradient.put("type", "linear");
    gradient.putArray("stops").addObject().put("offset", 2.0).put("color", "#000000");

    assertThat(policyRejection(policy).path())
        .isEqualTo("style.node.fill_gradient.stops[0].offset");
  }

  @Test
  void nodeStyleWithBothShapeAndDecoratorIsRejectedAtItsPath() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy
        .putObject("style")
        .putObject("node")
        .put("shape", "ellipse")
        .put("decorator", "archimate_business_actor");

    assertThat(policyRejection(policy).path()).isEqualTo("style.node.shape");
  }

  // --- RenderMetadataUsageException branches: assert the published code and its path ----------

  @Test
  void typeAwarePolicyWithoutSemanticProfileIsRejected() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.putObject("style").putObject("node_type_overrides").putObject("Foo").put("fill", "#abc");
    // no semantic_profile declared

    RenderInputValidator.RenderMetadataUsageException failure = metadataRejection(policy, null);
    assertThat(failure.code()).isEqualTo("DEDIREN_RENDER_METADATA_PROFILE_REQUIRED");
    assertThat(failure.path()).isEqualTo("semantic_profile");
  }

  @Test
  void typeAwarePolicyWithoutRenderMetadataIsRejected() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.put("semantic_profile", "generic");
    policy.putObject("style").putObject("node_type_overrides").putObject("Foo").put("fill", "#abc");

    RenderInputValidator.RenderMetadataUsageException failure = metadataRejection(policy, null);
    assertThat(failure.code()).isEqualTo("DEDIREN_RENDER_METADATA_REQUIRED");
    assertThat(failure.path()).isEqualTo("render_metadata");
  }

  @Test
  void renderMetadataProfileMismatchIsRejected() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.put("semantic_profile", "generic");
    policy
        .putObject("style")
        .putObject("edge_type_overrides")
        .putObject("Bar")
        .put("stroke", "#abc");

    RenderInputValidator.RenderMetadataUsageException failure =
        metadataRejection(policy, metadata(metadataNode("other")));
    assertThat(failure.code()).isEqualTo("DEDIREN_RENDER_METADATA_PROFILE_MISMATCH");
    assertThat(failure.path()).isEqualTo("render_metadata.semantic_profile");
  }

  // --- UML render-metadata content branches (the largest previously-uncovered region) --------

  @Test
  void umlMessageMetadataWithoutSequenceIsRejected() throws Exception {
    ObjectNode meta = umlMetadata();
    meta.putObject("edges").putObject("m1").put("type", "Message").putObject("properties");

    RenderInputValidator.RenderMetadataUsageException failure =
        metadataRejection(umlPolicy(), metadata(meta));
    assertThat(failure.code()).isEqualTo("DEDIREN_UML_MESSAGE_METADATA_INVALID");
    assertThat(failure.path()).isEqualTo("render_metadata.edges.m1.properties.sequence");
  }

  @Test
  void umlCombinedFragmentMetadataWithoutOperatorIsRejected() throws Exception {
    ObjectNode meta = umlMetadata();
    meta.putObject("nodes")
        .putObject("cf1")
        .put("type", "CombinedFragment")
        .putObject("properties");

    RenderInputValidator.RenderMetadataUsageException failure =
        metadataRejection(umlPolicy(), metadata(meta));
    assertThat(failure.code()).isEqualTo("DEDIREN_UML_COMBINED_FRAGMENT_METADATA_INVALID");
    assertThat(failure.path()).isEqualTo("render_metadata.nodes.cf1.properties.operator");
  }

  @Test
  void umlInteractionOperandMetadataWithoutCombinedFragmentIsRejected() throws Exception {
    ObjectNode meta = umlMetadata();
    meta.putObject("nodes")
        .putObject("op1")
        .put("type", "InteractionOperand")
        .putObject("properties");

    RenderInputValidator.RenderMetadataUsageException failure =
        metadataRejection(umlPolicy(), metadata(meta));
    assertThat(failure.code()).isEqualTo("DEDIREN_UML_INTERACTION_OPERAND_METADATA_INVALID");
    assertThat(failure.path()).isEqualTo("render_metadata.nodes.op1.properties.combined_fragment");
  }

  // --- Plumbing --------------------------------------------------------------------------------

  private static RenderInputValidator.PolicyValidationException policyRejection(
      ObjectNode policyNode) {
    return catchThrowableOfType(
        RenderInputValidator.PolicyValidationException.class, () -> validate(policyNode, null));
  }

  private static RenderInputValidator.RenderMetadataUsageException metadataRejection(
      ObjectNode policyNode, RenderMetadata metadata) {
    return catchThrowableOfType(
        RenderInputValidator.RenderMetadataUsageException.class,
        () -> validate(policyNode, metadata));
  }

  private static void validate(ObjectNode policyNode, RenderMetadata metadata) throws Exception {
    RenderPolicy policy = JsonSupport.objectMapper().treeToValue(policyNode, RenderPolicy.class);
    RenderInputValidator.validate(LAYOUT, metadata, policy);
  }

  /** The checked-in default SVG policy (page + margin present, no style), parsed fresh per test. */
  private static ObjectNode defaultPolicy() throws Exception {
    return (ObjectNode) RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json");
  }

  /** The default policy declaring the {@code uml} semantic profile, for UML-metadata tests. */
  private static ObjectNode umlPolicy() throws Exception {
    ObjectNode policy = defaultPolicy();
    policy.put("semantic_profile", "uml");
    return policy;
  }

  private static ObjectNode umlMetadata() {
    return metadataNode("uml");
  }

  private static ObjectNode metadataNode(String profile) {
    ObjectNode node = JsonSupport.objectMapper().createObjectNode();
    node.put("semantic_profile", profile);
    return node;
  }

  private static RenderMetadata metadata(ObjectNode metadataNode) {
    return JsonSupport.objectMapper().treeToValue(metadataNode, RenderMetadata.class);
  }

  private static LayoutResult loadBasicLayout() {
    try {
      return JsonSupport.objectMapper()
          .treeToValue(
              RenderTestSupport.fixtureJson("fixtures/layout-result/basic.json"),
              LayoutResult.class);
    } catch (Exception error) {
      throw new IllegalStateException("Could not load the basic layout fixture", error);
    }
  }
}
