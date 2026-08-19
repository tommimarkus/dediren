package dev.dediren.plugins.drawio.mx;

import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

/**
 * A parsed draw.io document: its pages in document order.
 *
 * <p>A bare {@code <mxGraphModel>} document — what draw.io's Extras ▸ Edit Diagram yields, and so
 * what a user pastes — produces exactly one unnamed page here, so every consumer sees one shape
 * regardless of which of the two accepted roots the input used.
 *
 * <p>Never empty: a document with no page is refused as {@code DEDIREN_DRAWIO_UNSUPPORTED_DOCUMENT}
 * rather than modelled as an empty file.
 */
public record MxFile(List<MxDiagram> diagrams) {

  public MxFile {
    diagrams = ContractCollections.listOrEmpty(diagrams);
  }
}
