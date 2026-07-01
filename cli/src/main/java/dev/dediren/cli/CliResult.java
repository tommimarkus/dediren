package dev.dediren.cli;

public record CliResult(int exitCode, String stdout, String stderr) {}
