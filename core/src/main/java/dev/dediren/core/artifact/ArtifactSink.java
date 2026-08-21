package dev.dediren.core.artifact;

import dev.dediren.core.analysis.Provenance;

/**
 * Owns what an engine's output artifact is stamped with and what its file is called, keyed on the
 * artifact kind alone.
 *
 * <p>Artifact kinds are {@code <format>+<serialization>} on both the render and export lanes
 * ({@code svg+xml}, {@code ascii+text}, {@code archimate-oef+xml}). The serialization decides
 * whether and how the artifact can be stamped; the format decides what the file is called.
 *
 * <p>Before this class the two questions were answered inline in {@code BuildCommand} and {@code
 * PackageBuildCommand}. Every render artifact was pushed through {@code Provenance.stampSvg}
 * regardless of kind, and that method returns its input <em>unchanged</em> when it finds no {@code
 * <svg>} root — so a non-SVG render artifact would have been written as though stamped, then
 * reported {@code unstamped} by {@code dediren status} with nothing in the build lane having said
 * so. Not-stampable is now a value the caller receives rather than a silent no-op.
 */
public final class ArtifactSink {

  /**
   * A stamped artifact's content, and whether a stamp was actually applied. {@code stamped ==
   * false} is a deliberate answer for serializations that carry no comment or metadata syntax, not
   * a failure — the caller decides what to do about it.
   */
  public record Stamped(String content, boolean stamped) {}

  /** How a serialization carries its provenance stamp. */
  private enum StampStyle {
    /** Inside the root element, as SVG's standard {@code <metadata>} child. */
    XML_METADATA_ELEMENT,
    /** Ahead of the root element, as an XML comment. */
    XML_LEADING_COMMENT,
    /** No stamp: the serialization has no inert place to put one. */
    NONE
  }

  private ArtifactSink() {}

  /**
   * Stamps {@code content} according to {@code artifactKind}'s serialization, or reports that the
   * kind cannot carry a stamp.
   *
   * @throws IllegalArgumentException if the kind is malformed or names a serialization with no
   *     stamping rule — failing loudly rather than writing an unstamped file that looks stamped.
   */
  public static Stamped stamp(String artifactKind, String content, String payloadJson) {
    return switch (styleFor(artifactKind)) {
      case XML_METADATA_ELEMENT -> new Stamped(Provenance.stampSvg(content, payloadJson), true);
      case XML_LEADING_COMMENT -> new Stamped(Provenance.stampXml(content, payloadJson), true);
      case NONE -> new Stamped(content, false);
    };
  }

  /**
   * The file extension for a render artifact. The format token is normally the extension itself
   * ({@code svg+xml} writes {@code diagram.svg}), so a new renderer needs no entry here; {@code
   * ascii} is the one exception, its conventional extension being {@code txt} rather than its
   * format name.
   *
   * <p>Note this deliberately does <em>not</em> reuse {@link #exportExtension}: the export lane
   * names files by serialization ({@code oef.xml}), so deriving a render file name the same way
   * would write an SVG to {@code diagram.xml}.
   */
  public static String renderExtension(String artifactKind) {
    String format = format(artifactKind);
    return "ascii".equals(format) ? "txt" : format;
  }

  /** The file extension for an export artifact, which names files by serialization. */
  public static String exportExtension(String artifactKind) {
    return switch (serialization(artifactKind)) {
      case "xml" -> "xml";
      case "json" -> "json";
      case "text" -> "txt";
      default -> throw unknownKind(artifactKind);
    };
  }

  private static StampStyle styleFor(String artifactKind) {
    return switch (serialization(artifactKind)) {
      // SVG owns a standard inert child element; everything else XML takes a leading comment,
      // which is the only placement valid ahead of an arbitrary root.
      case "xml" ->
          "svg".equals(format(artifactKind))
              ? StampStyle.XML_METADATA_ELEMENT
              : StampStyle.XML_LEADING_COMMENT;
      // A character grid has no comment syntax, and a leading line would corrupt the diagram.
      case "text" -> StampStyle.NONE;
      // JSON has no comment syntax either. The pre-sink code stamped every export artifact with
      // stampXml regardless of serialization, so a "<id>+json" export was written as an XML comment
      // followed by the document — not parseable as JSON by anything. No first-party engine emits
      // +json today, so nothing shipped depended on it.
      case "json" -> StampStyle.NONE;
      default -> throw unknownKind(artifactKind);
    };
  }

  private static String format(String artifactKind) {
    int suffix = artifactKind.lastIndexOf('+');
    if (suffix < 1) {
      throw unknownKind(artifactKind);
    }
    return artifactKind.substring(0, suffix);
  }

  private static String serialization(String artifactKind) {
    int suffix = artifactKind.lastIndexOf('+');
    if (suffix < 1 || suffix == artifactKind.length() - 1) {
      throw unknownKind(artifactKind);
    }
    return artifactKind.substring(suffix + 1);
  }

  private static IllegalArgumentException unknownKind(String artifactKind) {
    return new IllegalArgumentException(
        "no artifact rule for artifact kind "
            + artifactKind
            + "; expected <format>+<serialization>");
  }
}
