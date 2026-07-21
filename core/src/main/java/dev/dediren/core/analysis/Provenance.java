package dev.dediren.core.analysis;

import dev.dediren.contracts.json.JsonSupport;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Deterministic provenance stamps for build-lane artifacts: an inert {@code <metadata
 * id="dediren-provenance">} element in SVG and a leading {@code <!-- dediren-provenance ... -->}
 * comment in OEF/XMI, each carrying compact JSON with the model's canonical hash, the view id, the
 * lane policy's hash, and the tool version — never a timestamp, so stamped artifacts stay
 * byte-stable. Stamping happens after each engine has validated its own content; a comment or
 * metadata element cannot invalidate either format.
 *
 * <p>Every stamped value is product-generated (hashes, schema id, version) or charset-constrained
 * (view ids), and the JSON is XML-escaped at injection, so no untrusted text reaches the artifact
 * verbatim.
 */
public final class Provenance {

  static final String MARKER = "dediren-provenance";

  private Provenance() {}

  /** The stamp payload for one artifact lane. */
  public static String payload(
      String modelSchemaVersion,
      String modelSha256,
      String viewId,
      String policyField,
      String policySha256,
      String dedirenVersion) {
    ObjectNode payload = JsonSupport.objectMapper().createObjectNode();
    payload.put("model_schema_version", modelSchemaVersion);
    payload.put("model_sha256", modelSha256);
    payload.put("view_id", viewId);
    payload.put(policyField, policySha256);
    payload.put("dediren_version", dedirenVersion);
    return JsonSupport.objectMapper().writeValueAsString(payload);
  }

  /** Injects the stamp as the first child of the root {@code <svg>} element. */
  public static String stampSvg(String svg, String payloadJson) {
    int rootStart = svg.indexOf("<svg");
    if (rootStart < 0) {
      return svg;
    }
    int rootEnd = svg.indexOf('>', rootStart);
    if (rootEnd < 0) {
      return svg;
    }
    String metadata = "<metadata id=\"" + MARKER + "\">" + escapeXml(payloadJson) + "</metadata>";
    if (svg.charAt(rootEnd - 1) == '/') {
      // A self-closing root has no inside; reopen it so the stamp is a child, not a sibling.
      return svg.substring(0, rootEnd - 1) + ">" + metadata + "</svg>" + svg.substring(rootEnd + 1);
    }
    return svg.substring(0, rootEnd + 1) + metadata + svg.substring(rootEnd + 1);
  }

  /** Injects the stamp as a comment before the XML document's root element. */
  public static String stampXml(String xml, String payloadJson) {
    // XML comments must not contain "--"; the payload is product-generated JSON (hex hashes,
    // schema ids, constrained view ids), so the substring cannot occur, but escape defensively.
    String comment = "<!-- " + MARKER + " " + payloadJson.replace("--", "&#45;&#45;") + " -->\n";
    int declarationEnd = xml.startsWith("<?xml") ? xml.indexOf("?>") + 2 : 0;
    String head = xml.substring(0, declarationEnd);
    String tail = xml.substring(declarationEnd);
    if (tail.startsWith("\n")) {
      return head + "\n" + comment + tail.substring(1);
    }
    return head + comment + tail;
  }

  /** Extracts the stamp payload from a stamped artifact, if present. */
  public static Optional<JsonNode> extract(String content) {
    int marker = content.indexOf(MARKER);
    if (marker < 0) {
      return Optional.empty();
    }
    int start = content.indexOf('{', marker);
    if (start < 0) {
      return Optional.empty();
    }
    int depth = 0;
    for (int i = start; i < content.length(); i++) {
      char c = content.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          String json = unescapeXml(content.substring(start, i + 1));
          try {
            return Optional.of(JsonSupport.objectMapper().readTree(json));
          } catch (RuntimeException error) {
            return Optional.empty();
          }
        }
      }
    }
    return Optional.empty();
  }

  private static String escapeXml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }

  private static String unescapeXml(String text) {
    return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
  }
}
