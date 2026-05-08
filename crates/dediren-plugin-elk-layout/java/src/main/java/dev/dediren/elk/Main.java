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
        try {
            mapper.readValue(stdin, JsonContracts.LayoutRequest.class);
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_NOT_IMPLEMENTED",
                "ELK layout engine has not been wired yet"));
            return 3;
        } catch (Exception error) {
            stdout.println(EnvelopeWriter.error(
                mapper,
                "DEDIREN_ELK_INPUT_INVALID_JSON",
                "layout request JSON is invalid: " + error.getMessage()));
            return 3;
        }
    }
}
