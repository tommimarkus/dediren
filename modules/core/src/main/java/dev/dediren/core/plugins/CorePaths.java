package dev.dediren.core.plugins;

import java.nio.file.Files;
import java.nio.file.Path;

final class CorePaths {
    private CorePaths() {
    }

    static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("schemas/model.schema.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate repository root from user.dir");
    }
}
