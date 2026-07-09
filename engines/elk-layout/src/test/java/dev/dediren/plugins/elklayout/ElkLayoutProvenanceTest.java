package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.layout.GroupProvenance;
import dev.dediren.contracts.layout.LayoutConstraint;
import dev.dediren.contracts.layout.LayoutDensity;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.contracts.layout.LayoutEdge;
import dev.dediren.contracts.layout.LayoutEndpointMerging;
import dev.dediren.contracts.layout.LayoutGroup;
import dev.dediren.contracts.layout.LayoutNode;
import dev.dediren.contracts.layout.LayoutPreferences;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.LayoutRoutingPreferences;
import dev.dediren.contracts.layout.LayoutRoutingProfile;
import dev.dediren.contracts.layout.LayoutRoutingStyle;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElkLayoutProvenanceTest {
  @Test
  void layoutCarriesSourcePointerFromRequestToResult() {
    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "view-1",
            List.of(
                new LayoutNode("a", "A", "a", 40.0, 30.0, null, null, null, "/nodes/0"),
                new LayoutNode("b", "B", "b", 40.0, 30.0, null, null, null, "/nodes/1")),
            List.of(new LayoutEdge("e1", "a", "b", "", "e1", "flow", null, "/relationships/0")),
            List.of(),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertThat(result.nodes()).allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(result.edges().get(0).sourcePointer()).isEqualTo("/relationships/0");
  }

  // Regression coverage for the grouped layout path (ElkLayoutEngine#layoutGrouped), which builds
  // its own LaidOutNode/LaidOutEdge instances separately from the flat path above (§ElkLayoutEngine
  // lines ~424, ~450). A regression that forgot to thread nodePointers/edgePointers through this
  // second construction site would drop sourcePointer to null here while the flat-path test above
  // kept passing.
  @Test
  void groupedLayoutCarriesSourcePointerFromRequestToResult() {
    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "grouped-view",
            List.of(
                new LayoutNode(
                    "web-app", "Web App", "web-app", 160.0, 80.0, null, null, null, "/nodes/0"),
                new LayoutNode(
                    "orders-api",
                    "Orders API",
                    "orders-api",
                    160.0,
                    80.0,
                    null,
                    null,
                    null,
                    "/nodes/1"),
                new LayoutNode(
                    "database",
                    "PostgreSQL",
                    "database",
                    160.0,
                    80.0,
                    null,
                    null,
                    null,
                    "/nodes/2")),
            List.of(
                new LayoutEdge(
                    "web-app-calls-api",
                    "web-app",
                    "orders-api",
                    "calls API",
                    "web-app-calls-api",
                    "flow",
                    null,
                    "/relationships/0"),
                new LayoutEdge(
                    "api-writes-database",
                    "orders-api",
                    "database",
                    "writes orders",
                    "api-writes-database",
                    "flow",
                    null,
                    "/relationships/1")),
            List.of(
                new LayoutGroup(
                    "application-services",
                    "Application Services",
                    List.of("web-app", "orders-api"),
                    GroupProvenance.semanticBacked("application-services")),
                new LayoutGroup(
                    "external-dependencies",
                    "External Dependencies",
                    List.of("database"),
                    GroupProvenance.semanticBacked("external-dependencies"))),
            List.of(),
            null);

    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertThat(result.nodes()).hasSize(3);
    assertThat(result.nodes()).allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(result.edges()).hasSize(2);
    assertThat(result.edges())
        .allSatisfy(e -> assertThat(e.sourcePointer()).startsWith("/relationships/"));
  }

  // Regression coverage for the UML sequence path. Sequence requests are routed through
  // layoutFlat's construction site (same as the plain test above) and then rewritten by
  // SequenceLayoutConstraints#normalize, which rebuilds LaidOutNode/LaidOutEdge a second and third
  // time (lifeline-column normalization, interaction-band normalization, message-edge
  // normalization) at their own construction sites. Each of those sites must re-thread
  // nodePointers/edgePointers explicitly; this codebase has a documented history of the sequence
  // path dropping details that the flat path gets right by construction (see
  // seq-diagram-defects-poc-uljas memory), so this is exercised directly rather than assumed.
  @Test
  void sequenceLayoutCarriesSourcePointerFromRequestToResult() {
    LayoutRequest request =
        new LayoutRequest(
            ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION,
            "sequence-view",
            List.of(
                new LayoutNode(
                    "customer",
                    "Customer",
                    "customer",
                    140.0,
                    48.0,
                    "lifeline",
                    null,
                    null,
                    "/nodes/0"),
                new LayoutNode(
                    "service",
                    "Order Service",
                    "service",
                    140.0,
                    48.0,
                    "lifeline",
                    null,
                    null,
                    "/nodes/1"),
                new LayoutNode(
                    "interaction-place-order",
                    "Place Order",
                    "interaction-place-order",
                    360.0,
                    260.0,
                    "interaction",
                    null,
                    null,
                    "/nodes/2")),
            List.of(
                new LayoutEdge(
                    "m1",
                    "customer",
                    "service",
                    "placeOrder",
                    "m1",
                    "Message",
                    null,
                    "/relationships/0"),
                new LayoutEdge(
                    "m2",
                    "service",
                    "customer",
                    "accepted",
                    "m2",
                    "Message",
                    null,
                    "/relationships/1")),
            List.of(),
            List.of(
                new LayoutConstraint(
                    "sequence-view.uml.sequence.lifeline-order",
                    "uml.sequence.lifeline-order",
                    List.of("customer", "service")),
                new LayoutConstraint(
                    "sequence-view.uml.sequence.message-order",
                    "uml.sequence.message-order",
                    List.of("m1", "m2"))),
            new LayoutPreferences(
                LayoutDirection.RIGHT,
                LayoutDensity.READABLE,
                null,
                new LayoutRoutingPreferences(
                    LayoutRoutingStyle.ORTHOGONAL,
                    LayoutRoutingProfile.READABLE,
                    LayoutEndpointMerging.OFF)));

    LayoutResult result = new ElkLayoutEngine().layout(request);

    assertThat(result.nodes()).hasSize(3);
    assertThat(result.nodes()).allSatisfy(n -> assertThat(n.sourcePointer()).startsWith("/nodes/"));
    assertThat(result.edges()).hasSize(2);
    assertThat(result.edges())
        .allSatisfy(e -> assertThat(e.sourcePointer()).startsWith("/relationships/"));
  }
}
