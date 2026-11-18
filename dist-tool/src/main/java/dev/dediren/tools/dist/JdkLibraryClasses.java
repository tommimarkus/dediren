package dev.dediren.tools.dist;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Locates the JDK classes the bundle shrinker resolves references against. A JDK that ships {@code
 * jmods} is read in place. JDK 24+ vendor builds linked from the runtime image (JEP 493) ship no
 * {@code jmods}, and ProGuard cannot read the {@code lib/modules} image, so for those the classes
 * of the JVM running the build are exported from its {@code jrt:} filesystem into a scratch jar.
 */
final class JdkLibraryClasses {

  private static final String JMODS = "<java.home>/jmods(!**.jar;!module-info.class)";

  private JdkLibraryClasses() {}

  /**
   * Returns the ProGuard {@code -libraryjars} value for {@code javaHome}, writing {@code
   * scratchJar} only when that JDK has no {@code jmods} directory.
   */
  static String libraryJarsArg(Path javaHome, Path scratchJar) throws IOException {
    if (Files.isDirectory(javaHome.resolve("jmods"))) {
      return JMODS;
    }
    exportRuntimeImage(scratchJar);
    return scratchJar.toString();
  }

  private static void exportRuntimeImage(Path jar) throws IOException {
    Path modules = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules");
    List<Path> classes;
    try (Stream<Path> files = Files.walk(modules)) {
      classes =
          files
              .filter(file -> file.getNameCount() > 2)
              .filter(file -> file.getFileName().toString().endsWith(".class"))
              .filter(file -> !file.getFileName().toString().equals("module-info.class"))
              .sorted()
              .toList();
    }
    Set<String> written = new HashSet<>();
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
      out.setLevel(Deflater.BEST_SPEED);
      for (Path file : classes) {
        // /modules/<module>/<package path>/<Name>.class -> <package path>/<Name>.class
        String name = file.subpath(2, file.getNameCount()).toString();
        if (written.add(name)) {
          out.putNextEntry(new ZipEntry(name));
          Files.copy(file, out);
          out.closeEntry();
        }
      }
    }
  }
}
