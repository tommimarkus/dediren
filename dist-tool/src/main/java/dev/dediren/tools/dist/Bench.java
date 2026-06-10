package dev.dediren.tools.dist;

import java.util.ArrayList;
import java.util.List;

final class Bench {
    private Bench() {
    }

    record Stat(String command, int runs, long minMs, long medianMs, long maxMs) {
    }

    static Stat summarize(String command, List<Long> millis) {
        if (millis.isEmpty()) {
            throw new IllegalArgumentException("no samples for " + command);
        }
        List<Long> sorted = new ArrayList<>(millis);
        sorted.sort(Long::compareTo);
        long min = sorted.get(0);
        long max = sorted.get(sorted.size() - 1);
        long median = sorted.get((sorted.size() - 1) / 2);
        return new Stat(command, millis.size(), min, median, max);
    }
}
