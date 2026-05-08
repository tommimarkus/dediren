package dev.dediren.elk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.io.PrintStream;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.exit(run(System.in, System.out));
    }

    static int run(InputStream stdin, PrintStream stdout) throws Exception {
        ObjectMapper mapper = JsonContracts.objectMapper();
        JsonContracts.LayoutRequest request;
        try {
            request = mapper.readValue(stdin, JsonContracts.LayoutRequest.class);
        } catch (Exception error) {
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_INPUT_INVALID_JSON",
                "layout request JSON is invalid: " + error.getMessage()));
            return 3;
        }

        try {
            JsonContracts.LayoutResult result = new ElkLayoutEngine().layout(request);
            stdout.println(EnvelopeWriter.ok(mapper, result));
            return 0;
        } catch (Exception error) {
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_LAYOUT_FAILED",
                "ELK layout failed: " + error.getMessage()));
            return 3;
        }
    }
}
