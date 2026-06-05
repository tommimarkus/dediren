package dev.dediren.schemacache;

public record SchemaFetchResult(
        boolean succeeded,
        String command,
        int exitCode,
        byte[] stdout,
        byte[] stderr) {
    public static SchemaFetchResult success() {
        return new SchemaFetchResult(true, "schema fetcher", 0, new byte[0], new byte[0]);
    }
}
