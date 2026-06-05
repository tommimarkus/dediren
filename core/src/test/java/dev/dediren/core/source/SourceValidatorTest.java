package dev.dediren.core.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceValidatorTest {
    @TempDir
    Path temp;

    @Test
    void validateSourceUsesExplicitBundleRootOutsideCurrentWorkingDirectory() throws Exception {
        Path bundleRoot = temp.resolve("bundle-root");
        Path outsideBundle = temp.resolve("outside-bundle");
        Files.createDirectories(bundleRoot.resolve("schemas"));
        Files.createDirectories(outsideBundle);
        Files.writeString(bundleRoot.resolve("schemas/model.schema.json"), "{}");
        String originalUserDir = System.getProperty("user.dir");
        String originalBundleRoot = System.getProperty("dediren.bundle.root");
        System.setProperty("user.dir", outsideBundle.toString());
        System.setProperty("dediren.bundle.root", bundleRoot.toString());
        try {
            ValidationResult result = SourceValidator.validateSourceJson("""
                    {
                      "model_schema_version": "model.schema.v1",
                      "nodes": [],
                      "relationships": [],
                      "plugins": { "generic-graph": { "views": [] } }
                    }
                    """, null);

            assertThat(result.exitCode()).isZero();
        } finally {
            restoreProperty("user.dir", originalUserDir);
            restoreProperty("dediren.bundle.root", originalBundleRoot);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
