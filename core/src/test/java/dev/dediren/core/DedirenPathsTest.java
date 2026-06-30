package dev.dediren.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DedirenPathsTest {
    @Test
    void resolvesProductRootByWalkingUpFromWorkingDirWithoutTouchingGlobals(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("schemas"));
        Files.writeString(temp.resolve("schemas/model.schema.json"), "{}");
        Path nested = temp.resolve("a/b/c");
        Files.createDirectories(nested);

        Path root = DedirenPaths.productRoot(name -> null, name -> null, nested);

        assertThat(root).isEqualTo(temp);
    }

    @Test
    void prefersConfiguredBundleRootPropertyOverWorkingDirWalk(@TempDir Path temp) throws Exception {
        Files.createDirectories(temp.resolve("schemas"));
        Files.writeString(temp.resolve("schemas/model.schema.json"), "{}");

        Path root = DedirenPaths.productRoot(
                name -> DedirenPaths.BUNDLE_ROOT_PROPERTY.equals(name) ? temp.toString() : null,
                name -> null,
                Path.of("/dediren-nonexistent-working-dir"));

        assertThat(root).isEqualTo(temp.toAbsolutePath().normalize());
    }
}
