package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaAssertionsTest {
    private static final String MODEL_SCHEMA = "schemas/model.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path root = TestSupport.workspaceRoot();

    @Test
    void compileReturnsEmptyForAValidSchemaFile() {
        assertThat(SchemaAssertions.compile(root, MODEL_SCHEMA)).isEmpty();
    }

    @Test
    void compileReturnsErrorMessageForMissingSchemaFile() {
        List<String> errors = SchemaAssertions.compile(root, "schemas/does-not-exist.schema.json");

        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isNotBlank();
    }

    @Test
    void validateFixtureReturnsEmptyForASchemaValidDocument() {
        assertThat(SchemaAssertions.validateFixture(root, MODEL_SCHEMA, "fixtures/source/valid-basic.json"))
                .isEmpty();
    }

    @Test
    void validateReturnsSortedNonEmptyErrorsForADocumentMissingRequiredProperties() throws Exception {
        JsonNode empty = MAPPER.readTree("{}");

        List<String> errors = SchemaAssertions.validate(root, MODEL_SCHEMA, empty);

        assertThat(errors).hasSizeGreaterThan(1);
        assertThat(errors).isSorted();
    }

    @Test
    void assertSchemaValidDoesNotThrowForAValidDocument() throws Exception {
        JsonNode valid = MAPPER.readTree(root.resolve("fixtures/source/valid-basic.json").toFile());

        SchemaAssertions.assertSchemaValid(root, MODEL_SCHEMA, valid);
    }

    @Test
    void assertSchemaValidThrowsAssertionErrorForAnInvalidDocument() {
        JsonNode invalid = MAPPER.createObjectNode();

        assertThatThrownBy(() -> SchemaAssertions.assertSchemaValid(root, MODEL_SCHEMA, invalid))
                .isInstanceOf(AssertionError.class);
    }
}
