package dev.dediren.mcp;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.core.DedirenPaths;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema.ReadResourceResult;
import io.modelcontextprotocol.spec.McpSchema.Resource;
import io.modelcontextprotocol.spec.McpSchema.TextResourceContents;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.node.ArrayNode;

/**
 * Read-only MCP resources serving the bundle's own bytes: every public schema, every fixture, the
 * guide topics, and a generated diagnostics catalog. Serving the shipped files verbatim is the
 * anti-drift strategy — an agent that fetches {@code dediren://schema/model.schema.json} gets
 * ground truth, not a prose paraphrase of it.
 *
 * <p>Everything here is product-owned content resolved from the product root; no resource touches a
 * workspace path, so the {@code --root} confinement boundary is not involved and the full set is
 * served identically under {@code --read-only}.
 */
final class DedirenResources {

  private DedirenResources() {}

  static List<SyncResourceSpecification> specifications() {
    Path productRoot = DedirenPaths.productRoot();
    var specifications = new ArrayList<SyncResourceSpecification>();
    for (Path file : sortedFiles(productRoot.resolve("schemas"), 1)) {
      specifications.add(
          fileResource("dediren://schema/" + file.getFileName(), file, "application/json"));
    }
    Path fixtures = productRoot.resolve("fixtures");
    for (Path file : sortedFiles(fixtures, Integer.MAX_VALUE)) {
      String relative = fixtures.relativize(file).toString().replace('\\', '/');
      specifications.add(fileResource("dediren://fixture/" + relative, file, mimeOf(relative)));
    }
    for (String topic : GuideCatalog.topics()) {
      String uri = "dediren://guide/" + topic;
      specifications.add(
          new SyncResourceSpecification(
              Resource.builder(uri, topic).mimeType("text/markdown").build(),
              (exchange, request) ->
                  new ReadResourceResult(
                      List.of(
                          new TextResourceContents(
                              uri, "text/markdown", GuideCatalog.section(topic))))));
    }
    specifications.add(diagnosticsCatalog());
    return List.copyOf(specifications);
  }

  private static SyncResourceSpecification fileResource(String uri, Path file, String mimeType) {
    // Files.walk only yields regular files here, so a null getFileName() is impossible; the
    // requireNonNull states that for the static analyzer.
    String name = Objects.requireNonNull(file.getFileName(), "file name").toString();
    return new SyncResourceSpecification(
        Resource.builder(uri, name).mimeType(mimeType).build(),
        (exchange, request) ->
            new ReadResourceResult(
                List.of(new TextResourceContents(uri, mimeType, readFile(file)))));
  }

  /**
   * Every {@link DiagnosticCode} wire string paired with its explicit {@code ## Repair Rules}
   * bullet when the shipped guide names it (null otherwise — those codes are self-repairing via
   * their message). Generated from the two shipped truths at serve time, so the catalog cannot
   * drift from either.
   */
  private static SyncResourceSpecification diagnosticsCatalog() {
    String uri = "dediren://diagnostics/catalog";
    return new SyncResourceSpecification(
        Resource.builder(uri, "diagnostics-catalog").mimeType("application/json").build(),
        (exchange, request) -> {
          Map<String, String> repairRules = repairRules();
          ArrayNode catalog = JsonSupport.objectMapper().createArrayNode();
          for (DiagnosticCode code : DiagnosticCode.values()) {
            var entry = catalog.addObject();
            entry.put("code", code.code());
            entry.put("repair_rule", repairRules.get(code.code()));
          }
          return new ReadResourceResult(
              List.of(
                  new TextResourceContents(
                      uri,
                      "application/json",
                      JsonSupport.objectMapper().writeValueAsString(catalog))));
        });
  }

  /**
   * The guide's {@code ## Repair Rules} bullets keyed by every code named before the bullet's first
   * colon (a bullet may document several codes; codes mentioned in the repair text itself belong to
   * their own bullets). The bullet format is pinned by the catalog test, so a format drift fails
   * the build instead of silently emptying the catalog.
   */
  private static Map<String, String> repairRules() {
    var blocks = new ArrayList<StringBuilder>();
    for (String line : GuideCatalog.section("repair").split("\n", -1)) {
      if (line.startsWith("- ")) {
        blocks.add(new StringBuilder(line.substring(2).strip()));
      } else if (!blocks.isEmpty() && line.startsWith("  ")) {
        blocks.get(blocks.size() - 1).append(' ').append(line.strip());
      }
    }
    Pattern codePattern = Pattern.compile("DEDIREN_[A-Z0-9_]+");
    Map<String, String> byCode = new LinkedHashMap<>();
    for (StringBuilder block : blocks) {
      String text = block.toString();
      int colon = text.indexOf(':');
      if (colon < 0) {
        continue;
      }
      Matcher matcher = codePattern.matcher(text.substring(0, colon));
      while (matcher.find()) {
        byCode.putIfAbsent(matcher.group(), text);
      }
    }
    return byCode;
  }

  private static List<Path> sortedFiles(Path directory, int depth) {
    try (Stream<Path> walk = Files.walk(directory, depth)) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String readFile(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private static String mimeOf(String relativePath) {
    if (relativePath.endsWith(".json")) {
      return "application/json";
    }
    if (relativePath.endsWith(".xml") || relativePath.endsWith(".xmi")) {
      return "application/xml";
    }
    if (relativePath.endsWith(".md")) {
      return "text/markdown";
    }
    return "text/plain";
  }
}
