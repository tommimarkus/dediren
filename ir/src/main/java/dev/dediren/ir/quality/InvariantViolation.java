package dev.dediren.ir.quality;

import dev.dediren.ir.SourcePointer;

/**
 * A single failure of a named {@link SequenceInvariants} check against a {@link
 * dev.dediren.ir.LaidOutScene}.
 *
 * @param invariant the stable name of the violated invariant (e.g. {@code
 *     "message_endpoints_on_lifeline_axis"})
 * @param elementId the id of the offending node or edge
 * @param origin the offending element's provenance pointer into the source document, or {@code
 *     null} if it carries none
 * @param detail a human-readable explanation of the failure
 */
public record InvariantViolation(
    String invariant, String elementId, SourcePointer origin, String detail) {}
