package dev.dediren.plugins.drawio.mx;

import java.util.Objects;

/**
 * One cell of an mxGraph model: a vertex, an edge, a container, or one of the two structural cells
 * ({@code id="0"} and the default layer {@code id="1"}) every draw.io page opens with.
 *
 * <p>The {@code <mxCell>} attribute set is closed by the mxGraph format, so these are named fields
 * rather than a second raw map; open-ended custom attributes live on {@link MxObject}, which is the
 * format's own extension point. {@code style} is kept verbatim — unparsed and unnormalized —
 * because it is the mapper's only evidence of shape and, for ArchiMate stencils, layer.
 *
 * <p><strong>{@code id} and {@code value} are the effective values, not the raw ones.</strong> When
 * a cell is wrapped, the format moves its identity to the wrapper: the inner {@code <mxCell>} has no
 * {@code id} at all and its label is the wrapper's {@code @label}, not {@code @value}. The reader
 * resolves that here so no consumer has to, because a consumer that forgets is the classic draw.io
 * import bug — wrapped cells silently losing their labels and their edges losing their endpoints.
 * Nothing is lost by doing so: {@link MxObject#attributes()} still carries the wrapper's own {@code
 * id} and {@code label} unfiltered.
 *
 * <p>{@code parent}, {@code style}, {@code source}, {@code target}, {@code geometry}, and {@code
 * object} are null when the attribute or child element is absent. {@code value} is null for a cell
 * with no label, which draw.io distinguishes from {@code value=""}. {@code visible} defaults to
 * true, mirroring mxGraph, so only an explicit {@code visible="0"} makes it false.
 */
public record MxCell(
    String id,
    String parent,
    String value,
    String style,
    boolean vertex,
    boolean edge,
    String source,
    String target,
    boolean visible,
    MxGeometry geometry,
    MxObject object) {

  public MxCell {
    Objects.requireNonNull(id, "id");
  }

  /** True when this cell arrived inside an {@code <object>}/{@code <UserObject>} wrapper. */
  public boolean wrapped() {
    return object != null;
  }
}
