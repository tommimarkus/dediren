package dev.dediren.tools.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link DistTool#MCP_JAVA_SDK_CORE_JAR} to {@code mcp.sdk.version} in the root POM.
 *
 * <p>Licence attribution hangs off that bare string literal. Our own {@code mcp} module and the
 * third-party {@code io.modelcontextprotocol.sdk:mcp} artifact both ship a jar named {@code
 * mcp-<version>.jar}, so {@code DistTool} tells them apart by the SDK jar's exact filename;
 * anything that does not match falls through to the artifact-id lookup, resolves to {@code "mcp"},
 * and is classified as first-party.
 *
 * <p>Which means a routine dependency bump — {@code mcp.sdk.version} 2.0.0 to 2.1.0, one line, the
 * kind of change nobody reviews twice — would leave the literal pointing at a jar that no longer
 * exists, silently reclassify the SDK as our own code, and drop its MIT attribution out of
 * THIRD-PARTY-NOTICES.md. No error, no warning, green build, and a redistributed MIT library
 * shipped with no licence notice.
 *
 * <p>There is nothing clever to do about that except refuse to let the two drift apart.
 */
class McpSdkJarPinTest {

  @Test
  void sdkJarNameMatchesTheSdkVersionPropertyInTheRootPom() throws IOException {
    String version = mcpSdkVersion();

    assertThat(DistTool.MCP_JAVA_SDK_CORE_JAR)
        .as(
            "DistTool.MCP_JAVA_SDK_CORE_JAR has drifted from the root POM's <mcp.sdk.version>"
                + " (%s). It is the ONLY thing that stops the MCP Java SDK jar from being"
                + " misclassified as our own first-party 'mcp' module, which would silently drop"
                + " its MIT attribution from THIRD-PARTY-NOTICES.md. Update the literal in"
                + " DistTool to \"mcp-%s.jar\", and check whether the SDK's licence text bundled"
                + " at dist-tool/src/main/resources/dev/dediren/tools/dist/licenses/"
                + "mit-mcp-java-sdk.txt still matches the new release.",
            version, version)
        .isEqualTo("mcp-" + version + ".jar");
  }

  private static String mcpSdkVersion() throws IOException {
    Path pom = dev.dediren.testsupport.TestSupport.workspaceRoot().resolve("pom.xml");
    String text = Files.readString(pom, StandardCharsets.UTF_8);
    Matcher matcher =
        Pattern.compile("<mcp\\.sdk\\.version>([^<]+)</mcp\\.sdk\\.version>").matcher(text);
    if (!matcher.find()) {
      throw new IllegalStateException(
          "root pom.xml no longer declares <mcp.sdk.version>; DistTool.MCP_JAVA_SDK_CORE_JAR can no"
              + " longer be pinned to it, and the MCP SDK's licence attribution is unguarded");
    }
    return matcher.group(1).trim();
  }
}
