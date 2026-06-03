package dev.dediren.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

public final class DedirenPaths {
    public static final String BUNDLE_ROOT_PROPERTY = "dediren.bundle.root";
    public static final String BUNDLE_ROOT_ENV = "DEDIREN_BUNDLE_ROOT";

    private DedirenPaths() {
    }

    public static Path productRoot() {
        Optional<Path> configuredRoot = configuredRoot(BUNDLE_ROOT_PROPERTY, System::getProperty)
                .or(() -> configuredRoot(BUNDLE_ROOT_ENV, System::getenv));
        if (configuredRoot.isPresent()) {
            return requireProductRoot(configuredRoot.get(), configuredRootSource());
        }

        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (isProductRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Could not locate Dediren product root from " + BUNDLE_ROOT_PROPERTY
                        + ", " + BUNDLE_ROOT_ENV + ", or user.dir");
    }

    private static Optional<Path> configuredRoot(String name, Function<String, String> source) {
        String value = source.apply(name);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(value).toAbsolutePath().normalize());
    }

    private static String configuredRootSource() {
        return configuredRoot(BUNDLE_ROOT_PROPERTY, System::getProperty).isPresent()
                ? BUNDLE_ROOT_PROPERTY
                : BUNDLE_ROOT_ENV;
    }

    private static Path requireProductRoot(Path root, String source) {
        if (isProductRoot(root)) {
            return root;
        }
        throw new IllegalStateException(
                "Configured Dediren product root from " + source
                        + " does not contain schemas/model.schema.json: " + root);
    }

    private static boolean isProductRoot(Path path) {
        return Files.exists(path.resolve("schemas/model.schema.json"));
    }
}
