package dev.dediren.plugins.drawio.mx;

import dev.dediren.contracts.util.ContractCollections;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code <object>} (or {@code <UserObject>}) element wrapping an {@code <mxCell>}.
 *
 * <p><strong>Why this is its own record rather than fields on {@link MxCell}.</strong> The wrapper
 * is the format's extension point: its attribute set is open, and it is where draw.io puts a
 * tooltip, a link, placeholder flags, and every custom attribute an author adds — including the
 * {@code dediren*} attributes this project round-trips. Flattening an open attribute set onto
 * {@link MxCell}, whose own attribute set is closed by the mxGraph format, would mix a bounded
 * vocabulary with an unbounded one on the same record. It would also erase a distinction the mapper
 * and the export lane both need: with a map field alone, "wrapped, but carrying no custom
 * attributes" and "not wrapped at all" both read as an empty map, yet only the first means the
 * label came from {@code @label} and only the first must be re-emitted as a wrapper.
 *
 * <p>{@code attributes} is the wrapper's <em>complete, unfiltered</em> attribute map in document
 * order, {@code id} and {@code label} included, so nothing the reader normalized onto {@link
 * MxCell} is lost. {@code elementName} is {@code "object"} or {@code "UserObject"} verbatim:
 * draw.io writes both for the same construct, and an exporter that re-emits the wrong one produces
 * a file draw.io reads differently.
 */
public record MxObject(String elementName, Map<String, String> attributes) {

  public MxObject {
    Objects.requireNonNull(elementName, "elementName");
    attributes = ContractCollections.mapOrEmpty(attributes);
  }
}
