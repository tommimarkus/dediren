package dev.dediren.core.analysis;

import dev.dediren.contracts.json.JsonSupport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The canonical-hash mini-contract behind provenance stamps: SHA-256 over the document's canonical
 * JSON — object keys recursively sorted, compact separators, UTF-8. Formatting, key order, and
 * fragment layout of the on-disk file never change the hash; only semantic content does. No
 * timestamps enter anywhere, so the whole chain stays byte-stable.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  public static String sha256(JsonNode document) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] canonical =
          JsonSupport.objectMapper()
              .writeValueAsString(sortKeys(document))
              .getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(digest.digest(canonical));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 must be available", error);
    }
  }

  private static JsonNode sortKeys(JsonNode node) {
    if (node.isObject()) {
      var sorted = new TreeMap<String, JsonNode>();
      node.properties().forEach(entry -> sorted.put(entry.getKey(), sortKeys(entry.getValue())));
      ObjectNode result = JsonSupport.objectMapper().createObjectNode();
      sorted.forEach(result::set);
      return result;
    }
    if (node.isArray()) {
      ArrayNode result = JsonSupport.objectMapper().createArrayNode();
      node.forEach(child -> result.add(sortKeys(child)));
      return result;
    }
    return node;
  }
}
