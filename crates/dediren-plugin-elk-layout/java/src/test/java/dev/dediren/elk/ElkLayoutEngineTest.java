package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ElkLayoutEngineTest {
    @Test
    void layeredLayoutPlacesTargetToTheRightAndRoutesTheEdge() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(
                new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0),
                new JsonContracts.LayoutNode("api", "API", "api", 160.0, 80.0)),
            List.of(new JsonContracts.LayoutEdge(
                "client-calls-api", "client", "api", "calls", "client-calls-api")),
            List.of(),
            List.of(),
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutNode client = result.nodes().stream()
            .filter(node -> node.id().equals("client"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutNode api = result.nodes().stream()
            .filter(node -> node.id().equals("api"))
            .findFirst()
            .orElseThrow();
        JsonContracts.LaidOutEdge edge = result.edges().get(0);

        assertEquals("layout-result.schema.v1", result.layout_result_schema_version());
        assertEquals("main", result.view_id());
        assertEquals("client", client.source_id());
        assertEquals("api", api.projection_id());
        assertEquals("client-calls-api", edge.source_id());
        assertEquals("client-calls-api", edge.projection_id());
        assertTrue(api.x() > client.x(), "layered layout should place target after source");
        assertTrue(edge.points().size() >= 2, "layout must include start and end points");
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void partialGroupUsesLaidOutMembersAndSemanticSourceId() {
        JsonContracts.LayoutRequest request = new JsonContracts.LayoutRequest(
            "layout-request.schema.v1",
            "main",
            List.of(new JsonContracts.LayoutNode("client", "Client", "client", 160.0, 80.0)),
            List.of(),
            List.of(new JsonContracts.LayoutGroup(
                "group-1",
                "Group",
                List.of("client", "missing"),
                new JsonContracts.GroupProvenance(
                    new JsonContracts.SemanticBacked("system-group")))),
            List.of(),
            List.of());

        JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);

        JsonContracts.LaidOutGroup group = result.groups().get(0);

        assertEquals("system-group", group.source_id());
        assertEquals(List.of("client"), group.members());
        assertTrue(result.warnings().stream()
            .anyMatch(warning ->
                warning.code().equals("DEDIREN_ELK_MISSING_GROUP_MEMBER")
                    && warning.path().equals("$.groups[0].members[1]")));
    }
}
