package dev.dediren.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One owned reader for repository fixtures, resolved under the workspace root. Replaces the
 * per-test-class {@code workspaceRoot()}/{@code readString} copies scattered across modules.
 */
public final class Fixtures {
    private Fixtures() {
    }

    /** Resolves a repository-relative path under the workspace root. */
    public static Path path(String relative) {
        return TestSupport.workspaceRoot().resolve(relative);
    }

    /** Reads a repository-relative fixture as a UTF-8 string. */
    public static String read(String relative) throws IOException {
        return Files.readString(path(relative));
    }
}
