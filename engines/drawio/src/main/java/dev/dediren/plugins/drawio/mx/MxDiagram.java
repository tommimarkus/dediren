package dev.dediren.plugins.drawio.mx;

import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

/**
 * One {@code <diagram>} page and its cells, in document order.
 *
 * <p>{@code id} and {@code name} are null when the page carries no such attribute; a bare {@code
 * <mxGraphModel>} document has neither, and draw.io itself omits {@code name} on some older files.
 *
 * <p>{@code compressed} records how this page arrived, detected structurally — whether the {@code
 * <diagram>} held an {@code <mxGraphModel>} child or character data — and never from the {@code
 * compressed=} attribute, which recent draw.io omits entirely. It is provenance for the export
 * lane, not an instruction: the cells below are decoded either way.
 */
public record MxDiagram(String id, String name, boolean compressed, List<MxCell> cells) {

  public MxDiagram {
    cells = ContractCollections.listOrEmpty(cells);
  }
}
