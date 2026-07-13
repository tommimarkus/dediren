package dev.dediren.contracts.render;

import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

/**
 * A node/group gradient fill. {@code angle} (degrees, linear only; 0 = left→right, 90 = top→bottom)
 * is applied over the shape's bounding box. Rendered as an inline {@code <linearGradient>} / {@code
 * <radialGradient>} referenced by a deterministic id.
 */
public record SvgGradient(SvgGradientType type, Double angle, List<SvgGradientStop> stops) {
  public SvgGradient {
    stops = ContractCollections.copyOrNull(stops);
  }
}
