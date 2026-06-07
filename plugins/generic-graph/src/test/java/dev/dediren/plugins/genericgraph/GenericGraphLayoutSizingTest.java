package dev.dediren.plugins.genericgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.source.SourceNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GenericGraphLayoutSizingTest {
    @Test
    void archimateRelationshipConnectorsUseCompactSizeHints() {
        SourceNode connector = new SourceNode("junction", "AndJunction", "And", Map.of());

        assertThat(GenericGraphLayoutSizing.widthHint("archimate", connector)).isEqualTo(28.0);
        assertThat(GenericGraphLayoutSizing.heightHint("archimate", connector)).isEqualTo(28.0);
    }

    @Test
    void umlClassifierSizingUsesCompartmentText() throws Exception {
        JsonNode uml = JsonSupport.objectMapper().readTree("""
                {
                  "attributes": [
                    {"visibility": "private", "name": "a", "type": "B"},
                    {"visibility": "private", "name": "c", "type": "D"},
                    {"visibility": "private", "name": "e", "type": "F"}
                  ],
                  "operations": [
                    {"visibility": "public", "name": "g", "return_type": "H"},
                    {"visibility": "public", "name": "i", "return_type": "J"}
                  ]
                }
                """);
        // 26-char label dominates width; 3 attrs + 2 ops push height above the 120 floor.
        SourceNode classifier = new SourceNode(
                "repository", "Class", "CustomerRepositoryGatewayA", Map.of("uml", uml));

        // width  = roundUp(max(26*8 + 32, 220), 20) = roundUp(240, 20) = 240.0
        // height = roundUp(max(28 + (3*14+8) + (2*14+8) + 14, 120), 10) = roundUp(128, 10) = 130.0
        assertThat(GenericGraphLayoutSizing.widthHint("uml", classifier)).isEqualTo(240.0);
        assertThat(GenericGraphLayoutSizing.heightHint("uml", classifier)).isEqualTo(130.0);
    }
}
