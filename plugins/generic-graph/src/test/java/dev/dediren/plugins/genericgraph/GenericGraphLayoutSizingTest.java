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
                    {"visibility": "private", "name": "customerIdentifier", "type": "String"}
                  ],
                  "operations": [
                    {
                      "visibility": "public",
                      "name": "lookupCustomerByIdentifier",
                      "return_type": "Customer",
                      "parameters": [
                        {"name": "identifier", "type": "String"}
                      ]
                    }
                  ]
                }
                """);
        SourceNode classifier = new SourceNode("repository", "Class", "CustomerRepository", Map.of("uml", uml));

        assertThat(GenericGraphLayoutSizing.widthHint("uml", classifier)).isGreaterThan(220.0);
        assertThat(GenericGraphLayoutSizing.heightHint("uml", classifier)).isGreaterThanOrEqualTo(120.0);
    }
}
