package dev.dediren.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves docs/agent-usage.md to agents one section at a time.
 *
 * <p>The guide is 800+ lines (~12-15k tokens). Returning it whole on every call would defeat the
 * token efficiency the document is explicitly written for, so the tool is a progressive-disclosure
 * surface: the agent asks for the topic it needs.
 *
 * <p>{@code TOPICS} is curated rather than derived from the headings, so topic ids stay stable and
 * agent-facing even when a heading is reworded. {@code GuideCatalogTest} pins the map against the
 * document in both directions: every topic must resolve to a real heading, and every heading must
 * be reachable from some topic — so a newly added section cannot go silently unreachable.
 */
public final class GuideCatalog {
  private static final String RESOURCE = "/dediren/agent-usage.md";
  // Topic additions move together with their agent-usage sections; GuideCatalogTest is
  // bidirectional, so an unreachable section or a dangling topic fails the build.

  private static final Map<String, String> TOPICS = topicMap();

  private static final Map<String, String> SECTIONS = loadSections();

  private GuideCatalog() {}

  private static Map<String, String> topicMap() {
    Map<String, String> topics = new LinkedHashMap<>();
    topics.put("fast-path", "Fast Path");
    topics.put("mcp", "MCP Server");
    topics.put("artifacts", "Artifact Map");
    topics.put("source-json", "Minimal Source JSON");
    topics.put("fragments", "Fragments");
    topics.put("profiles", "Semantic Profiles");
    topics.put("archimate", "ArchiMate Handoff");
    topics.put("commands", "Command Handoff");
    topics.put("build", "Build");
    topics.put("diff-query", "Diff & Query");
    topics.put("provenance", "Provenance & Verify");
    topics.put("render-policy", "Render Policy Options");
    topics.put("uml-sequence", "UML Sequence Handoff");
    topics.put("uml-state-machine", "UML State Machine Handoff");
    topics.put("uml-use-case", "UML Use Case Handoff");
    topics.put("uml-component", "UML Component Handoff");
    topics.put("uml-deployment", "UML Deployment Handoff");
    topics.put("runtime-probes", "Runtime Probes");
    topics.put("smoke", "Bundle Smoke Workflow");
    topics.put("export", "Export");
    topics.put("repair", "Repair Rules");
    topics.put("migration", "Migration");
    topics.put("environment", "Plugin Environment");
    topics.put("logging", "Debug Logging");
    topics.put("redistribution", "Redistribution");
    return Map.copyOf(topics);
  }

  /** The heading this topic maps to. */
  public static String headingFor(String topic) {
    return TOPICS.get(topic);
  }

  /** Every topic id, in declaration order. */
  public static List<String> topics() {
    return List.copyOf(TOPICS.keySet());
  }

  /** Every {@code ##} heading in the bundled guide, in document order. */
  public static List<String> headings() {
    return List.copyOf(SECTIONS.keySet());
  }

  /** A short markdown index of the available topics. */
  public static String index() {
    StringBuilder out = new StringBuilder();
    out.append("# Dediren agent guide — topics\n\n");
    out.append("Call `dediren_guide` again with one of these `topic` values:\n\n");
    TOPICS.forEach(
        (topic, heading) ->
            out.append("- `").append(topic).append("` — ").append(heading).append('\n'));
    return out.toString();
  }

  /**
   * Whether {@code topic} resolves to a real section, as opposed to the unknown-topic message. The
   * one definition of "known", so {@code section} and the MCP tool's {@code isError} flag cannot
   * disagree about it.
   *
   * <p>Null-tolerant on purpose: both backing maps are {@code Map.copyOf} results, which throw on a
   * null key rather than returning null. The only caller today null-checks first, but this is
   * public and the next one might not.
   */
  public static boolean hasSection(String topic) {
    if (topic == null) {
      return false;
    }
    String heading = TOPICS.get(topic);
    return heading != null && SECTIONS.containsKey(heading);
  }

  /** The markdown for one topic, or an "unknown topic" message naming the valid topics. */
  public static String section(String topic) {
    if (!hasSection(topic)) {
      return "unknown topic '" + topic + "'. Valid topics: " + String.join(", ", topics());
    }
    return SECTIONS.get(TOPICS.get(topic));
  }

  private static Map<String, String> loadSections() {
    String guide = read();
    Map<String, String> sections = new LinkedHashMap<>();
    String heading = null;
    List<String> body = new ArrayList<>();
    for (String line : guide.split("\n", -1)) {
      if (line.startsWith("## ")) {
        if (heading != null) {
          sections.put(heading, String.join("\n", body));
        }
        heading = line.substring(3).strip();
        body = new ArrayList<>();
        body.add(line);
      } else if (heading != null) {
        body.add(line);
      }
    }
    if (heading != null) {
      sections.put(heading, String.join("\n", body));
    }
    return Map.copyOf(sections);
  }

  private static String read() {
    try (InputStream stream = GuideCatalog.class.getResourceAsStream(RESOURCE)) {
      if (stream == null) {
        throw new IllegalStateException(
            "bundled guide "
                + RESOURCE
                + " is missing — the maven-resources-plugin copy of"
                + " docs/agent-usage.md did not run");
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }
}
