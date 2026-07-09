package dev.dediren.semantics.graph;

/** Captured stdout/stderr/exit of one {@link RouterHarness} invocation. */
record RouterResult(int exitCode, String stdout, String stderr) {}
