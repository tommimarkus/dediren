package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.management.HotSpotDiagnosticMXBean;
import dev.dediren.testsupport.TestSupport;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JvmSecurityOptionsTest {
  @Test
  void testJvmDisablesVulnerableBase64Intrinsics() {
    assertThat(
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                .getVMOption("UseBASE64Intrinsics")
                .getValue())
        .as("Java4Shell (CVE-2026-114938) mitigation in the forked test JVM")
        .isEqualTo("false");
  }

  @ParameterizedTest
  @ValueSource(strings = {".mvn/jvm.config", "dediren.launcher.jvmArgs"})
  void configuredJvmDisablesBase64IntrinsicsEvenAfterConflictingUserOption(
      String source, @TempDir Path temp) throws Exception {
    Path root = TestSupport.workspaceRoot();
    List<String> flags;
    if (source.equals(".mvn/jvm.config")) {
      flags =
          Arrays.asList(
              Files.readString(root.resolve(source), StandardCharsets.UTF_8).trim().split("\\s+"));
    } else {
      String pom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
      var property =
          Pattern.compile("<dediren.launcher.jvmArgs>([^<]+)</dediren.launcher.jvmArgs>")
              .matcher(pom);
      assertThat(property.find()).as("packaged launcher JVM options").isTrue();
      flags = Arrays.asList(property.group(1).trim().split("\\s+"));
    }

    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-XX:+UseBASE64Intrinsics");
    command.addAll(flags);
    command.add("-XX:+PrintFlagsFinal");
    command.add("-version");
    Path output = temp.resolve("jvm-flags.txt");
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    // Ambient options must not supply the mitigation that this configuration is meant to enforce.
    builder.environment().remove("JAVA_TOOL_OPTIONS");
    builder.environment().remove("JDK_JAVA_OPTIONS");
    builder.environment().remove("_JAVA_OPTIONS");
    Process process = builder.start();
    try {
      assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("JVM flag probe completed").isTrue();
      String result = Files.readString(output, StandardCharsets.UTF_8);
      assertThat(process.exitValue()).as("%s JVM startup: %s", source, result).isZero();
      assertThat(result)
          .as("%s must disable the vulnerable Base64 intrinsic", source)
          .containsPattern("UseBASE64Intrinsics\\s+=\\s+false\\s");
    } finally {
      process.destroyForcibly();
    }
  }
}
