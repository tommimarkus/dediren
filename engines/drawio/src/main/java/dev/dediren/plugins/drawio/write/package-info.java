/**
 * The draw.io export half of the lane: the {@code (SourceDocument, LayoutResult)} → {@code MxFile}
 * mapping and the {@code dediren*} identity attributes that mapping writes. Serialising the
 * resulting model is {@code mx}'s job, and reading one back is {@code read}'s.
 */
package dev.dediren.plugins.drawio.write;
