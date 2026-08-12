package dev.dediren.plugins.mermaid;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.layout.LayoutDirection;
import dev.dediren.engine.EngineException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Iterative parser for Dediren's deliberately bounded Mermaid flowchart subset. */
final class MermaidParser {
  static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;
  static final int MAX_ELEMENTS = 100_000;
  static final int MAX_STATEMENTS = 200_000;
  static final int MAX_NESTING = 256;
  static final int MAX_TOKEN_BYTES = 64 * 1024;

  private final Map<String, MutableNode> nodes = new LinkedHashMap<>();
  private final List<ParsedEdge> edges = new ArrayList<>();
  private final List<MutableGroup> groups = new ArrayList<>();
  private final Deque<MutableGroup> openGroups = new ArrayDeque<>();
  private final Map<String, Integer> hints = new LinkedHashMap<>();
  private LayoutDirection direction;
  private boolean headerSeen;
  private int statements;

  ParsedDiagram parse(String source) throws EngineException {
    if (source == null) {
      throw failure(DiagnosticCode.MERMAID_SYNTAX_INVALID, "Mermaid source is required", 1, 1);
    }
    if (utf8Length(source) > MAX_INPUT_BYTES) {
      throw limit(
          DiagnosticCode.MERMAID_INPUT_TOO_LARGE,
          "Mermaid source exceeds the 64 MiB input ceiling");
    }

    int line = 1;
    int start = 0;
    while (start <= source.length()) {
      int newline = source.indexOf('\n', start);
      int end = newline < 0 ? source.length() : newline;
      int contentEnd = end > start && source.charAt(end - 1) == '\r' ? end - 1 : end;
      parseLine(source.substring(start, contentEnd), line);
      if (newline < 0) {
        break;
      }
      start = newline + 1;
      line++;
    }
    if (!headerSeen) {
      throw failure(
          DiagnosticCode.MERMAID_UNSUPPORTED_DIAGRAM,
          "expected one Mermaid flowchart or graph diagram",
          1,
          1);
    }
    if (!openGroups.isEmpty()) {
      throw failure(
          DiagnosticCode.MERMAID_SYNTAX_INVALID,
          "subgraph is missing its closing end",
          Math.max(1, line),
          1);
    }
    List<ParsedNode> parsedNodes =
        nodes.values().stream().map(node -> new ParsedNode(node.id, node.label)).toList();
    List<ParsedGroup> parsedGroups =
        groups.stream()
            .map(group -> new ParsedGroup(group.id, group.label, List.copyOf(group.members)))
            .toList();
    return new ParsedDiagram(
        direction,
        parsedNodes,
        List.copyOf(edges),
        parsedGroups,
        Collections.unmodifiableMap(new LinkedHashMap<>(hints)));
  }

  private void parseLine(String rawLine, int line) throws EngineException {
    int comment = commentStart(rawLine);
    String lineText = comment < 0 ? rawLine : rawLine.substring(0, comment);
    int segmentStart = 0;
    int nesting = 0;
    boolean quoted = false;
    int quoteStart = -1;
    for (int index = 0; index <= lineText.length(); index++) {
      char current = index == lineText.length() ? ';' : lineText.charAt(index);
      if (current == '"') {
        quoted = !quoted;
        quoteStart = quoted ? index : -1;
      } else if (!quoted && (current == '[' || current == '(' || current == '{')) {
        nesting++;
      } else if (!quoted && (current == ']' || current == ')' || current == '}')) {
        nesting = Math.max(0, nesting - 1);
      }
      if (current == ';' && !quoted && nesting == 0) {
        String segment = lineText.substring(segmentStart, index);
        int leading = leadingWhitespace(segment);
        if (leading < segment.length()) {
          parseStatement(
              segment.substring(leading).stripTrailing(), line, segmentStart + leading + 1);
        }
        segmentStart = index + 1;
      }
    }
    if (quoted) {
      throw syntax("unterminated quoted label", line, quoteStart + 1);
    }
    if (nesting != 0) {
      String segment = lineText.substring(segmentStart);
      int leading = leadingWhitespace(segment);
      if (leading < segment.length()) {
        parseStatement(
            segment.substring(leading).stripTrailing(), line, segmentStart + leading + 1);
      }
    }
  }

  private void parseStatement(String statement, int line, int column) throws EngineException {
    if (!headerSeen) {
      parseHeader(statement, line, column);
      return;
    }
    statements++;
    if (statements > MAX_STATEMENTS) {
      throw limit(
          DiagnosticCode.MERMAID_STATEMENT_LIMIT_EXCEEDED,
          "Mermaid source exceeds the 200000 statement ceiling");
    }

    if (statement.equals("end")) {
      if (openGroups.isEmpty()) {
        throw failure(DiagnosticCode.MERMAID_SYNTAX_INVALID, "unexpected end", line, column);
      }
      openGroups.pop();
      return;
    }
    if (statement.startsWith("subgraph")) {
      parseSubgraph(statement, line, column);
      return;
    }
    if (statement.startsWith("direction ")) {
      if (openGroups.isEmpty()) {
        throw failure(
            DiagnosticCode.MERMAID_SYNTAX_INVALID,
            "direction is only accepted as a discarded subgraph layout hint",
            line,
            column);
      }
      hint("direction");
      return;
    }
    String hint = hintFamily(statement);
    if (hint != null) {
      checkToken(statement, line, column);
      hint(hint);
      return;
    }
    int unsupportedEdge = unsupportedEdgeIndex(statement);
    if (unsupportedEdge >= 0) {
      throw failure(
          DiagnosticCode.MERMAID_UNSUPPORTED_EDGE,
          "only directed solid Mermaid edges are supported",
          line,
          column + unsupportedEdge);
    }
    rejectUnsafe(statement, line, column);
    parseNodeOrChain(statement, line, column);
  }

  private void parseHeader(String statement, int line, int column) throws EngineException {
    String[] tokens = statement.split("\\s+");
    if (tokens.length != 2 || !(tokens[0].equals("flowchart") || tokens[0].equals("graph"))) {
      throw failure(
          DiagnosticCode.MERMAID_UNSUPPORTED_DIAGRAM,
          "only one flowchart or graph diagram is supported",
          line,
          column);
    }
    direction =
        switch (tokens[1]) {
          case "TB", "TD" -> LayoutDirection.DOWN;
          case "BT" -> LayoutDirection.UP;
          case "LR" -> LayoutDirection.RIGHT;
          case "RL" -> LayoutDirection.LEFT;
          default ->
              throw failure(
                  DiagnosticCode.MERMAID_UNSUPPORTED_CONSTRUCT,
                  "unsupported flowchart direction: " + tokens[1],
                  line,
                  column + statement.indexOf(tokens[1]));
        };
    headerSeen = true;
  }

  private void parseSubgraph(String statement, int line, int column) throws EngineException {
    if (!statement.startsWith("subgraph ")) {
      throw failure(
          DiagnosticCode.MERMAID_SYNTAX_INVALID,
          "subgraph requires an identifier",
          line,
          column + "subgraph".length());
    }
    String declaration = statement.substring("subgraph ".length()).strip();
    NodeToken token = parseNodeToken(declaration, 0, line, column + "subgraph ".length());
    if (token.next != declaration.length()
        || (declaration.indexOf('[') < 0 && declaration.substring(0, token.next).contains(" "))) {
      throw failure(
          DiagnosticCode.MERMAID_SYNTAX_INVALID,
          "subgraph identifiers containing spaces require an explicit [label]",
          line,
          column + "subgraph ".length());
    }
    if (openGroups.size() >= MAX_NESTING) {
      throw limit(
          DiagnosticCode.MERMAID_NESTING_LIMIT_EXCEEDED,
          "Mermaid source exceeds the 256 nested group ceiling");
    }
    MutableGroup group = new MutableGroup(token.id, token.label);
    groups.add(group);
    openGroups.push(group);
    checkElementLimit();
  }

  private void parseNodeOrChain(String statement, int line, int column) throws EngineException {
    NodeToken first = parseNodeToken(statement, 0, line, column);
    remember(first);
    int position = skipWhitespace(statement, first.next);
    if (position == statement.length()) {
      return;
    }
    String previous = first.id;
    while (position < statement.length()) {
      String label = null;
      if (statement.startsWith("-->", position)) {
        position += 3;
        position = skipWhitespace(statement, position);
        if (position < statement.length() && statement.charAt(position) == '|') {
          int close = statement.indexOf('|', position + 1);
          if (close < 0) {
            throw syntax("unterminated edge label", line, column + position);
          }
          label = statement.substring(position + 1, close);
          checkToken(label, line, column + position + 1);
          position = skipWhitespace(statement, close + 1);
        }
      } else if (statement.startsWith("--", position)) {
        int arrow = statement.indexOf("-->", position + 2);
        if (arrow < 0) {
          throw syntax("expected --> after edge label", line, column + position);
        }
        label = statement.substring(position + 2, arrow).strip();
        if (label.isEmpty()) {
          throw syntax("edge label is empty", line, column + position + 2);
        }
        checkToken(label, line, column + position + 2);
        position = skipWhitespace(statement, arrow + 3);
      } else {
        throw syntax("expected a directed edge", line, column + position);
      }
      if (position >= statement.length()) {
        throw syntax("expected a node after -->", line, column + position);
      }
      NodeToken next = parseNodeToken(statement, position, line, column);
      remember(next);
      edges.add(new ParsedEdge(previous, next.id, label));
      checkElementLimit();
      previous = next.id;
      position = skipWhitespace(statement, next.next);
    }
  }

  private NodeToken parseNodeToken(String text, int start, int line, int baseColumn)
      throws EngineException {
    int position = skipWhitespace(text, start);
    int idStart = position;
    while (position < text.length()) {
      char value = text.charAt(position);
      if (Character.isWhitespace(value)
          || value == '['
          || value == '('
          || value == '{'
          || value == '>'
          || (value == '-' && position + 1 < text.length() && text.charAt(position + 1) == '-')) {
        break;
      }
      position++;
    }
    if (position == idStart) {
      throw syntax("expected a node identifier", line, baseColumn + position);
    }
    String id = text.substring(idStart, position);
    checkToken(id, line, baseColumn + idStart);
    String label = id;
    if (position < text.length() && !Character.isWhitespace(text.charAt(position))) {
      Shape shape = shapeAt(text, position);
      if (shape == null) {
        throw syntax("unsupported node declaration", line, baseColumn + position);
      }
      int close = text.indexOf(shape.suffix, position + shape.prefix.length());
      if (close < 0) {
        throw syntax("unterminated node label", line, baseColumn + position);
      }
      label = text.substring(position + shape.prefix.length(), close);
      if (label.length() >= 2 && label.startsWith("\"") && label.endsWith("\"")) {
        label = label.substring(1, label.length() - 1);
      }
      checkToken(label, line, baseColumn + position + shape.prefix.length());
      hint("node shape");
      position = close + shape.suffix.length();
    }
    return new NodeToken(id, label, position);
  }

  private static Shape shapeAt(String text, int position) {
    String[] prefixes = {"[[", "([", "[(", "((", "{{", "[/", "[\\", "[", "(", "{", ">"};
    String[] suffixes = {"]]", "])", ")]", "))", "}}", "\\]", "\\]", "]", ")", "}", "]"};
    for (int index = 0; index < prefixes.length; index++) {
      if (text.startsWith(prefixes[index], position)) {
        return new Shape(prefixes[index], suffixes[index]);
      }
    }
    return null;
  }

  private void remember(NodeToken token) throws EngineException {
    MutableNode existing = nodes.get(token.id);
    if (existing == null) {
      existing = new MutableNode(token.id, token.label);
      nodes.put(token.id, existing);
      checkElementLimit();
    } else if (!token.label.equals(token.id)) {
      existing.label = token.label;
    }
    for (MutableGroup group : openGroups) {
      group.members.add(token.id);
    }
  }

  private void rejectUnsafe(String statement, int line, int column) throws EngineException {
    String lower = statement.toLowerCase(Locale.ROOT);
    String[] prefixes = {"click ", "href ", "acctitle", "accdescr"};
    for (String prefix : prefixes) {
      if (lower.startsWith(prefix)) {
        throw failure(
            DiagnosticCode.MERMAID_UNSUPPORTED_CONSTRUCT,
            "interactive, accessibility directive, or external Mermaid content is unsupported",
            line,
            column);
      }
    }
    String[] markers = {"@{", "<", "`", "http://", "https://", "file:", "data:"};
    for (String marker : markers) {
      int at = lower.indexOf(marker);
      if (at >= 0) {
        throw failure(
            DiagnosticCode.MERMAID_UNSUPPORTED_CONSTRUCT,
            "HTML, interactive behavior, and external resources are unsupported",
            line,
            column + at);
      }
    }
  }

  private static int unsupportedEdgeIndex(String statement) {
    String[] markers = {"<--", "-.", "==", "~~~", "---", "--x", "--o", "x--", "o--"};
    int first = -1;
    for (String marker : markers) {
      int found = statement.indexOf(marker);
      if (found >= 0 && (first < 0 || found < first)) {
        first = found;
      }
    }
    return first;
  }

  private static String hintFamily(String statement) {
    if (statement.startsWith("classDef ")) {
      return "classDef";
    }
    if (statement.startsWith("linkStyle ")) {
      return "linkStyle";
    }
    if (statement.startsWith("style ")) {
      return "style";
    }
    if (statement.startsWith("class ")) {
      return "class";
    }
    return null;
  }

  private void hint(String family) {
    hints.merge(family, 1, Integer::sum);
  }

  private void checkElementLimit() throws EngineException {
    if (nodes.size() + edges.size() + groups.size() > MAX_ELEMENTS) {
      throw limit(
          DiagnosticCode.MERMAID_ELEMENT_LIMIT_EXCEEDED,
          "Mermaid source exceeds the 100000 produced element and group ceiling");
    }
  }

  private static void checkToken(String token, int line, int column) throws EngineException {
    if (utf8Length(token) > MAX_TOKEN_BYTES) {
      throw limit(
          DiagnosticCode.MERMAID_TOKEN_LIMIT_EXCEEDED,
          "Mermaid token or label exceeds the 64 KiB ceiling");
    }
    for (int index = 0; index < token.length(); index++) {
      if (Character.isISOControl(token.charAt(index))) {
        throw failure(
            DiagnosticCode.MERMAID_SYNTAX_INVALID,
            "control characters are not accepted in Mermaid tokens",
            line,
            column + index);
      }
    }
  }

  private static int commentStart(String line) {
    boolean quoted = false;
    for (int index = 0; index + 1 < line.length(); index++) {
      if (line.charAt(index) == '"') {
        quoted = !quoted;
      }
      if (!quoted && line.charAt(index) == '%' && line.charAt(index + 1) == '%') {
        return index;
      }
    }
    return -1;
  }

  private static int leadingWhitespace(String text) {
    return skipWhitespace(text, 0);
  }

  private static int skipWhitespace(String text, int start) {
    int position = start;
    while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
      position++;
    }
    return position;
  }

  private static long utf8Length(String value) {
    long bytes = 0;
    for (int index = 0; index < value.length(); ) {
      int codePoint = value.codePointAt(index);
      bytes += codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
      index += Character.charCount(codePoint);
    }
    return bytes;
  }

  private static EngineException syntax(String message, int line, int column) {
    return failure(DiagnosticCode.MERMAID_SYNTAX_INVALID, message, line, column);
  }

  private static EngineException failure(
      DiagnosticCode code, String message, int line, int column) {
    return EngineException.structuralFailure(
        code.code(), message, "line " + line + ", column " + column);
  }

  private static EngineException limit(DiagnosticCode code, String message) {
    return EngineException.structuralFailure(code.code(), message, "$");
  }

  private record NodeToken(String id, String label, int next) {}

  private record Shape(String prefix, String suffix) {}

  private static final class MutableNode {
    private final String id;
    private String label;

    private MutableNode(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }

  private static final class MutableGroup {
    private final String id;
    private final String label;
    private final LinkedHashSet<String> members = new LinkedHashSet<>();

    private MutableGroup(String id, String label) {
      this.id = id;
      this.label = label;
    }
  }
}
