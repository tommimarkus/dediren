package dev.dediren.testsupport;

import java.nio.file.Path;

public final class TestSupport {
    private TestSupport() {
    }

    public static Path workspaceRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
