package dev.dediren.contracts.render;

import static dev.dediren.contracts.util.ContractCollections.mapOrEmpty;

import java.util.Map;

public record SvgStylePolicy(
    SvgBackgroundStyle background,
    SvgFontStyle font,
    SvgNodeStyle node,
    SvgEdgeStyle edge,
    SvgGroupStyle group,
    Map<String, SvgNodeStyle> nodeTypeOverrides,
    Map<String, SvgEdgeStyle> edgeTypeOverrides,
    Map<String, SvgGroupStyle> groupTypeOverrides,
    Map<String, SvgNodeStyle> nodeOverrides,
    Map<String, SvgEdgeStyle> edgeOverrides,
    Map<String, SvgGroupStyle> groupOverrides) {
  public SvgStylePolicy {
    nodeTypeOverrides = mapOrEmpty(nodeTypeOverrides);
    edgeTypeOverrides = mapOrEmpty(edgeTypeOverrides);
    groupTypeOverrides = mapOrEmpty(groupTypeOverrides);
    nodeOverrides = mapOrEmpty(nodeOverrides);
    edgeOverrides = mapOrEmpty(edgeOverrides);
    groupOverrides = mapOrEmpty(groupOverrides);
  }
}
