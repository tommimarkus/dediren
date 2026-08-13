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
import java.util.Set;
import java.util.regex.Pattern;

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
  private static final Pattern SAFE_BR = Pattern.compile("(?i)<br(?:/| /)?>");
  private static final Set<String> STRUCTURAL_KEYWORDS =
      Set.of(
          "flowchart",
          "graph",
          "subgraph",
          "end",
          "direction",
          "style",
          "default",
          "interpolate",
          "class",
          "classdef",
          "linkstyle",
          "click",
          "href",
          "acctitle",
          "accdescr");
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
    PendingStatement pending = null;
    while (start <= source.length()) {
      int newline = source.indexOf('\n', start);
      int end = newline < 0 ? source.length() : newline;
      int contentEnd = end > start && source.charAt(end - 1) == '\r' ? end - 1 : end;
      String rawLine = source.substring(start, contentEnd);
      String lineText =
          pending != null && pending.needsContinuation() ? rawLine : withoutComment(rawLine);
      for (Segment segment : segments(lineText)) {
        if (segment.text().isBlank()) {
          continue;
        }
        String text = segment.text().strip();
        int column = segment.column() + leadingWhitespace(segment.text());
        if (pending == null) {
          pending = new PendingStatement(text, line, column);
        } else if (pending.needsContinuation() || beginsEdge(text)) {
          pending.append(text);
        } else {
          parseStatement(pending.text.toString(), pending.line, pending.column);
          pending = new PendingStatement(text, line, column);
        }
        if (segment.terminated()) {
          parseStatement(pending.text.toString(), pending.line, pending.column);
          pending = null;
        }
      }
      if (newline < 0) {
        break;
      }
      start = newline + 1;
      line++;
    }
    if (pending != null) {
      if (PendingStatement.unbalanced(pending.text)) {
        int opening = PendingStatement.unclosedStart(pending.text);
        throw syntax(
            "unterminated quoted label",
            logicalLine(pending.text.toString(), pending.line, opening),
            logicalColumn(pending.text.toString(), pending.column, opening));
      }
      parseStatement(pending.text.toString(), pending.line, pending.column);
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

  private static List<Segment> segments(String lineText) {
    List<Segment> result = new ArrayList<>();
    int segmentStart = 0;
    int nesting = 0;
    boolean quoted = false;
    int quoteStart = -1;
    for (int index = 0; index < lineText.length(); index++) {
      char current = lineText.charAt(index);
      if (current == '"') {
        quoted = !quoted;
        quoteStart = quoted ? index : -1;
      } else if (!quoted && (current == '[' || current == '(' || current == '{')) {
        nesting++;
      } else if (!quoted && (current == ']' || current == ')' || current == '}')) {
        nesting = Math.max(0, nesting - 1);
      }
      if (current == ';' && !quoted && nesting == 0) {
        result.add(new Segment(lineText.substring(segmentStart, index), segmentStart + 1, true));
        segmentStart = index + 1;
      }
    }
    result.add(new Segment(lineText.substring(segmentStart), segmentStart + 1, false));
    return result;
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
    rejectStructuralNodeId(statement, line, column);
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
      EdgeKind kind;
      if (statement.startsWith("-->", position)) {
        kind = EdgeKind.DIRECTED;
        position += 3;
        position = skipWhitespace(statement, position);
        if (position < statement.length() && statement.charAt(position) == '|') {
          int close = statement.indexOf('|', position + 1);
          if (close < 0) {
            throw syntax("unterminated edge label", line, column + position);
          }
          label = statement.substring(position + 1, close);
          checkLabel(label, line, column + position + 1);
          position = skipWhitespace(statement, close + 1);
        }
      } else if (statement.startsWith("---", position)) {
        kind = EdgeKind.UNDIRECTED;
        position += 3;
        position = skipWhitespace(statement, position);
        if (position < statement.length() && statement.charAt(position) == '|') {
          int close = statement.indexOf('|', position + 1);
          if (close < 0) {
            throw syntax("unterminated edge label", line, column + position);
          }
          label = statement.substring(position + 1, close);
          checkLabel(label, line, column + position + 1);
          position = skipWhitespace(statement, close + 1);
        }
        hint("undirected relationship (default-arrowhead / marker_end: none)");
      } else if (statement.startsWith("--", position)) {
        int directed = statement.indexOf("-->", position + 2);
        int undirected = statement.indexOf("---", position + 2);
        int arrow = earliest(directed, undirected);
        if (arrow < 0) {
          throw syntax("expected a supported edge after edge label", line, column + position);
        }
        kind = arrow == undirected ? EdgeKind.UNDIRECTED : EdgeKind.DIRECTED;
        label = statement.substring(position + 2, arrow).strip();
        if (label.isEmpty()) {
          throw syntax("edge label is empty", line, column + position + 2);
        }
        checkLabel(label, line, column + position + 2);
        position = skipWhitespace(statement, arrow + 3);
        if (kind == EdgeKind.UNDIRECTED) {
          hint("undirected relationship (default-arrowhead / marker_end: none)");
        }
      } else {
        throw syntax("expected a directed edge", line, column + position);
      }
      if (position >= statement.length()) {
        throw syntax(
            "expected a node after -->",
            logicalLine(statement, line, position),
            logicalColumn(statement, column, position)
                + (statement.lastIndexOf('\n', Math.max(0, position - 1)) < 0 ? 0 : 1));
      }
      NodeToken next = parseNodeToken(statement, position, line, column);
      remember(next);
      edges.add(new ParsedEdge(previous, next.id, normalizeLabel(label), kind));
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
    if (STRUCTURAL_KEYWORDS.contains(id.toLowerCase(Locale.ROOT))) {
      throw failure(
          DiagnosticCode.MERMAID_UNSUPPORTED_CONSTRUCT,
          "Mermaid structural keywords cannot be node identifiers",
          line,
          baseColumn + idStart);
    }
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
      checkLabel(label, line, baseColumn + position + shape.prefix.length());
      label = normalizeLabel(label);
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
    String[] markers = {"@{", "`", "http://", "https://", "file:", "data:"};
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
    for (int start = lower.indexOf('<'); start >= 0; start = lower.indexOf('<', start + 1)) {
      int end = lower.indexOf('>', start + 1);
      boolean safeBr = end >= 0 && SAFE_BR.matcher(lower.substring(start, end + 1)).matches();
      boolean labelContext =
          statement.lastIndexOf('[', start) >= 0 || statement.lastIndexOf("--", start) >= 0;
      if (!safeBr || !labelContext) {
        int bracket = statement.lastIndexOf('[', start);
        int location = bracket >= 0 && statement.indexOf("\n", bracket) < 0 ? bracket + 1 : start;
        throw failure(
            DiagnosticCode.MERMAID_UNSUPPORTED_CONSTRUCT,
            "HTML, interactive behavior, and external resources are unsupported",
            logicalLine(statement, line, location),
            logicalColumn(statement, column, location));
      }
    }
  }

  private static int unsupportedEdgeIndex(String statement) {
    String[] markers = {"<--", "-.", "==", "~~~", "--x", "--o", "x--", "o--"};
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

  private static String withoutComment(String line) {
    int comment = commentStart(line);
    return comment < 0 ? line : line.substring(0, comment);
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

  private static boolean beginsEdge(String text) {
    return text.startsWith("-->") || text.startsWith("---") || text.startsWith("--");
  }

  private static int earliest(int first, int second) {
    if (first < 0) {
      return second;
    }
    if (second < 0) {
      return first;
    }
    return Math.min(first, second);
  }

  private static String normalizeLabel(String label) {
    return label == null ? null : SAFE_BR.matcher(label).replaceAll("\n");
  }

  private static void checkLabel(String label, int line, int column) throws EngineException {
    checkToken(label.replace('\n', ' '), line, column);
  }

  private static int logicalLine(String statement, int line, int position) {
    int result = line;
    for (int index = 0; index < position; index++) {
      if (statement.charAt(index) == '\n') {
        result++;
      }
    }
    return result;
  }

  private static int logicalColumn(String statement, int column, int position) {
    int newline = statement.lastIndexOf('\n', Math.max(0, position - 1));
    return newline < 0 ? column + position : position - newline;
  }

  private static void rejectStructuralNodeId(String statement, int line, int column)
      throws EngineException {
    int end = 0;
    while (end < statement.length()
        && !Character.isWhitespace(statement.charAt(end))
        && statement.charAt(end) != '['
        && statement.charAt(end) != '('
        && statement.charAt(end) != '{') {
      end++;
    }
    if (end > 0
        && STRUCTURAL_KEYWORDS.contains(statement.substring(0, end).toLowerCase(Locale.ROOT))) {
      throw failure(
          DiagnosticCode.MERMAID_UNSUPPORTED_CONSTRUCT,
          "Mermaid structural keywords cannot be node identifiers",
          line,
          column);
    }
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

  private record Segment(String text, int column, boolean terminated) {}

  private static final class PendingStatement {
    private final StringBuilder text;
    private final int line;
    private final int column;

    private PendingStatement(String text, int line, int column) {
      this.text = new StringBuilder(text);
      this.line = line;
      this.column = column;
    }

    private void append(String next) {
      text.append('\n').append(next);
    }

    private boolean needsContinuation() {
      return unbalanced(text)
          || text.toString().stripTrailing().endsWith("-->")
          || text.toString().stripTrailing().endsWith("---");
    }

    private static boolean unbalanced(CharSequence source) {
      int nesting = 0;
      boolean quoted = false;
      for (int index = 0; index < source.length(); index++) {
        char current = source.charAt(index);
        if (current == '"') {
          quoted = !quoted;
        } else if (!quoted && (current == '[' || current == '(' || current == '{')) {
          nesting++;
        } else if (!quoted && (current == ']' || current == ')' || current == '}')) {
          nesting--;
        }
      }
      return quoted || nesting > 0;
    }

    private static int unclosedStart(CharSequence source) {
      int nesting = 0;
      int opening = source.length();
      boolean quoted = false;
      for (int index = 0; index < source.length(); index++) {
        char current = source.charAt(index);
        if (current == '"') {
          quoted = !quoted;
          if (quoted) {
            opening = index;
          }
        } else if (!quoted && (current == '[' || current == '(' || current == '{')) {
          if (nesting++ == 0) {
            opening = index;
          }
        } else if (!quoted && (current == ']' || current == ')' || current == '}')) {
          nesting--;
        }
      }
      return opening;
    }
  }

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
