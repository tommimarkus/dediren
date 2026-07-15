package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MergedJarPostProcessorTest {

  @Test
  void unionsServiceFilesAcrossOriginalJarsFilteredToSurvivingClasses(@TempDir Path dir)
      throws Exception {
    Path a =
        jar(
            dir.resolve("a.jar"),
            Map.of(
                "META-INF/services/com.example.Spi", "com.example.AlphaImpl\n",
                "com/example/AlphaImpl.class", "x"));
    // b.jar re-declares AlphaImpl (dedup case), adds BetaImpl, and declares GoneImpl whose
    // class did not survive shrinking.
    Path b =
        jar(
            dir.resolve("b.jar"),
            Map.of(
                "META-INF/services/com.example.Spi",
                "com.example.AlphaImpl\ncom.example.BetaImpl\n# comment\ncom.example.GoneImpl\n",
                "com/example/BetaImpl.class",
                "x"));
    // The merged jar as a first-wins merge leaves it: only a.jar's service file survived,
    // and GoneImpl's class was shrunk away.
    Path merged =
        jar(
            dir.resolve("merged.jar"),
            Map.of(
                "META-INF/services/com.example.Spi", "com.example.AlphaImpl\n",
                "com/example/AlphaImpl.class", "x",
                "com/example/BetaImpl.class", "x"));

    MergedJarPostProcessor.apply(merged, List.of(a, b));

    assertThat(entryText(merged, "META-INF/services/com.example.Spi"))
        .isEqualTo("com.example.AlphaImpl\ncom.example.BetaImpl\n");
  }

  @Test
  void relocatesEmbeddedLicenceFilesUnderThirdPartyNamespace(@TempDir Path dir) throws Exception {
    // docs/LICENSE.txt is nested content, not a jar-root licence artifact — it must NOT be
    // relocated (the production depth check exists exactly for this).
    Path guava =
        jar(
            dir.resolve("guava-33.6.0-jre.jar"),
            Map.of(
                "META-INF/LICENSE", "apache text",
                "docs/LICENSE.txt", "unrelated nested content",
                "com/G.class", "x"));
    Path elk =
        jar(
            dir.resolve("elk-core-0.11.0.jar"),
            Map.of(
                "about.html",
                "see about_files/epl-v20.html",
                "about_files/epl-v20.html",
                "epl text",
                "org/E.class",
                "x"));
    Path merged = jar(dir.resolve("merged.jar"), Map.of("com/G.class", "x", "org/E.class", "x"));

    MergedJarPostProcessor.apply(merged, List.of(guava, elk));

    assertThat(entryText(merged, "META-INF/third-party/guava-33.6.0-jre/LICENSE"))
        .isEqualTo("apache text");
    assertThat(entryText(merged, "META-INF/third-party/elk-core-0.11.0/about.html"))
        .isEqualTo("see about_files/epl-v20.html");
    // about.html's relative about_files/ links keep resolving in the relocated layout.
    assertThat(entryText(merged, "META-INF/third-party/elk-core-0.11.0/about_files/epl-v20.html"))
        .isEqualTo("epl text");
    // Non-licence resources are not relocated, and existing class entries survive untouched.
    assertThat(entryText(merged, "com/G.class")).isEqualTo("x");
    try (ZipFile zip = new ZipFile(merged.toFile())) {
      assertThat(zip.getEntry("META-INF/third-party/guava-33.6.0-jre/LICENSE.txt"))
          .as("nested docs/LICENSE.txt must not be treated as a licence artifact")
          .isNull();
    }
  }

  @Test
  void repacksEveryEntryStoredSoTheArchiveGzipCompressesRawBytes(@TempDir Path dir)
      throws Exception {
    Path merged =
        jar(
            dir.resolve("merged.jar"),
            Map.of("com/example/AlphaImpl.class", "x".repeat(4096), "resource.txt", "data"));

    MergedJarPostProcessor.apply(merged, List.of());

    try (ZipFile zip = new ZipFile(merged.toFile())) {
      var names = zip.entries();
      while (names.hasMoreElements()) {
        ZipEntry entry = names.nextElement();
        assertThat(entry.getMethod()).as(entry.getName()).isEqualTo(ZipEntry.STORED);
      }
    }
  }

  private static Path jar(Path path, Map<String, String> entries) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
      for (Map.Entry<String, String> entry : new java.util.TreeMap<>(entries).entrySet()) {
        out.putNextEntry(new ZipEntry(entry.getKey()));
        out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return path;
  }

  private static String entryText(Path jar, String name) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(name);
      assertThat(entry).as("entry %s in %s", name, jar).isNotNull();
      try (var in = zip.getInputStream(entry)) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
  }
}
