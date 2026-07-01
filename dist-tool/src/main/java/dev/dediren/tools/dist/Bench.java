package dev.dediren.tools.dist;

import dev.dediren.contracts.json.JsonSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Bench {
  private Bench() {}

  record Stat(String command, int runs, long minMs, long medianMs, long maxMs) {}

  static String renderReport(List<Stat> stats) throws Exception {
    List<Map<String, Object>> results = new ArrayList<>();
    for (Stat stat : stats) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("command", stat.command());
      row.put("runs", stat.runs());
      row.put("min_ms", stat.minMs());
      row.put("median_ms", stat.medianMs());
      row.put("max_ms", stat.maxMs());
      results.add(row);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("schema", "dediren-bench.v1");
    report.put("results", results);
    return JsonSupport.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report);
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
