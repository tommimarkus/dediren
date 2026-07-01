package dev.dediren.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

public final class DedirenPaths {
  public static final String BUNDLE_ROOT_PROPERTY = "dediren.bundle.root";
  public static final String BUNDLE_ROOT_ENV = "DEDIREN_BUNDLE_ROOT";

  private DedirenPaths() {}

  public static Path productRoot() {
    return productRoot(
        System::getProperty, System::getenv, Path.of(System.getProperty("user.dir")));
  }

  /**
   * Resolves the product root from explicit inputs: a configured {@code dediren.bundle.root}
   * property, then a {@code DEDIREN_BUNDLE_ROOT} environment variable, then a walk up from {@code
   * workingDir}. Pure with respect to JVM globals so callers and tests can resolve a root without
   * mutating {@code System} properties.
   */
  static Path productRoot(
      Function<String, String> properties, Function<String, String> env, Path workingDir) {
    Optional<Path> fromProperty = configuredRoot(BUNDLE_ROOT_PROPERTY, properties);
    if (fromProperty.isPresent()) {
      return requireProductRoot(fromProperty.get(), BUNDLE_ROOT_PROPERTY);
    }
    Optional<Path> fromEnv = configuredRoot(BUNDLE_ROOT_ENV, env);
    if (fromEnv.isPresent()) {
      return requireProductRoot(fromEnv.get(), BUNDLE_ROOT_ENV);
    }
    Path current = workingDir.toAbsolutePath();
    while (current != null) {
      if (isProductRoot(current)) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "Could not locate Dediren product root from "
            + BUNDLE_ROOT_PROPERTY
            + ", "
            + BUNDLE_ROOT_ENV
            + ", or working directory "
            + workingDir);
  }

  private static Optional<Path> configuredRoot(String name, Function<String, String> source) {
    String value = source.apply(name);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(Path.of(value).toAbsolutePath().normalize());
  }

  private static Path requireProductRoot(Path root, String source) {
    if (isProductRoot(root)) {
      return root;
    }
    throw new IllegalStateException(
        "Configured Dediren product root from "
            + source
            + " does not contain schemas/model.schema.json: "
            + root);
  }

  private static boolean isProductRoot(Path path) {
    return Files.exists(path.resolve("schemas/model.schema.json"));
  }
}
