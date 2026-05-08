package dev.dediren.elk;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MainTest {
    @Test
    void invalidJsonReturnsStructuredErrorEnvelope() throws Exception {
        ByteArrayInputStream stdin =
            new ByteArrayInputStream("not-json".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exitCode = Main.run(stdin, new PrintStream(stdout, true, StandardCharsets.UTF_8));

        String text = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(3, exitCode);
        assertTrue(text.contains("\"status\":\"error\""));
        assertTrue(text.contains("DEDIREN_ELK_INPUT_INVALID_JSON"));
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }
}
