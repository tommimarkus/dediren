package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdkLibraryClassesTest {

  @Test
  void jdkWithJmodsIsReadInPlace(@TempDir Path temp) throws Exception {
    Path javaHome = Files.createDirectories(temp.resolve("jdk"));
    Files.createDirectories(javaHome.resolve("jmods"));
    Path scratchJar = temp.resolve("jdk-classes.jar");

    String libraryJars = JdkLibraryClasses.libraryJarsArg(javaHome, scratchJar);

    assertThat(libraryJars).isEqualTo("<java.home>/jmods(!**.jar;!module-info.class)");
    assertThat(scratchJar).doesNotExist();
  }

  @Test
  void jdkWithoutJmodsExportsTheRunningRuntimeImage(@TempDir Path temp) throws Exception {
    // JDK 24+ vendor builds linked from the runtime image (JEP 493) ship no jmods directory, so the
    // shrinker's library classes have to come from the jrt image of the JVM running the build.
    Path javaHome = Files.createDirectories(temp.resolve("jdk-without-jmods"));
    Path scratchJar = temp.resolve("jdk-classes.jar");

    String libraryJars = JdkLibraryClasses.libraryJarsArg(javaHome, scratchJar);

    assertThat(libraryJars).isEqualTo(scratchJar.toString());
    try (ZipFile jar = new ZipFile(scratchJar.toFile())) {
      List<String> names = jar.stream().map(ZipEntry::getName).toList();
      assertThat(names)
          // java.base, plus modules the bundle really links against (java.xml, java.net.http).
          .contains(
              "java/lang/Object.class",
              "javax/xml/validation/SchemaFactory.class",
              "java/net/http/HttpClient.class")
          .doesNotHaveDuplicates()
          .allSatisfy(name -> assertThat(name).endsWith(".class"))
          .noneSatisfy(name -> assertThat(name).endsWith("module-info.class"));
    }
  }
}
