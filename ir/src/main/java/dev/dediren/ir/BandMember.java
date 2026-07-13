package dev.dediren.ir;

/** One member of an ordered band, with the leading gap reserved before it (0 when none). */
public record BandMember(String id, double leadingGap) {}
