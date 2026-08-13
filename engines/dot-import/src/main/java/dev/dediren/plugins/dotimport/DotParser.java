package dev.dediren.plugins.dotimport;

import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.engine.EngineException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recursive-descent parser over {@link DotLexer} tokens, producing a {@link DotDocument} AST.
 *
 * <p>Design choice: {@code node}/{@code edge}/{@code graph} default-attribute statements are not
 * themselves emitted as AST nodes. Instead every {@link DotNodeStatement} and {@link
 * DotEdgeStatement} carries its <em>already-resolved</em> attribute map — the defaults live in its
 * enclosing scope at that exact point of declaration, merged under any attributes explicit on that
 * statement. Each subgraph opens with a copy of its parent's current {@code node}/{@code edge}
 * defaults, so mutations inside never leak back out to a sibling or the parent once the subgraph
 * closes. This parser does not merge attributes across multiple mentions of the same node id (for
 * example a bare {@code a;} after an earlier {@code a [color=red];}) — each statement's resolved
 * attributes stand on their own; reconciling repeated mentions into one model element is mapping
 * work for a later step, not DOT parsing.
 *
 * <p>Never accepts the anonymous brace-only subgraph shorthand (a bare {@code { ... }} used as a
 * statement or edge endpoint without a preceding {@code subgraph} keyword) — real DOT allows it,
 * but this parser only supports {@code subgraph [ID] { ... }}, and reports every anonymous or
 * subgraph-as-edge-endpoint use as {@link DiagnosticCode#DOT_UNSUPPORTED_CONSTRUCT}.
 */
final class DotParser {
  private final List<DotToken> tokens;
  private int index;
  private boolean directed;
  private int statements;
  private final Set<String> declaredNodeIds = new HashSet<>();
  private int elements;

  private DotParser(List<DotToken> tokens) {
    this.tokens = tokens;
  }

  static DotDocument parse(String source) throws EngineException {
    if (source == null) {
      throw DotLimits.syntax("DOT source is required", 1, 1);
    }
    DotParser parser = new DotParser(DotLexer.tokenize(source));
    return parser.parseDocument();
  }

  private DotDocument parseDocument() throws EngineException {
    boolean strict = false;
    if (peek().isKeyword("strict")) {
      advance();
      strict = true;
    }
    DotToken kind = peek();
    if (kind.isKeyword("graph")) {
      directed = false;
    } else if (kind.isKeyword("digraph")) {
      directed = true;
    } else {
      throw DotLimits.syntax("expected 'graph' or 'digraph'", kind.line(), kind.column());
    }
    advance();

    String graphId = null;
    if (peek().type() == DotTokenType.ID) {
      graphId = expectId("expected a graph identifier").text();
    }

    expect(DotTokenType.LBRACE, "expected '{'");
    Scope scope = Scope.root();
    List<DotStatement> body = parseStatementList(scope, 0);
    expect(DotTokenType.RBRACE, "expected '}'");
    if (peek().type() != DotTokenType.EOF) {
      DotToken trailing = peek();
      throw DotLimits.syntax(
          "unexpected content after the closing '}'", trailing.line(), trailing.column());
    }
    return new DotDocument(strict, directed, graphId, Map.copyOf(scope.graphAttributes), body);
  }

  private List<DotStatement> parseStatementList(Scope scope, int depth) throws EngineException {
    List<DotStatement> statementsOut = new ArrayList<>();
    while (true) {
      while (peek().type() == DotTokenType.SEMI) {
        advance();
      }
      if (peek().type() == DotTokenType.RBRACE || peek().type() == DotTokenType.EOF) {
        return statementsOut;
      }
      parseStatement(scope, depth, statementsOut);
    }
  }

  private void parseStatement(Scope scope, int depth, List<DotStatement> out)
      throws EngineException {
    DotToken token = peek();
    if (token.type() == DotTokenType.LBRACE) {
      throw DotLimits.unsupported(
          "anonymous subgraph shorthand (bare '{ ... }') is not supported",
          token.line(),
          token.column());
    }
    if (token.isKeyword("subgraph")) {
      out.add(parseSubgraph(scope, depth));
      return;
    }
    if ((token.isKeyword("graph") || token.isKeyword("node") || token.isKeyword("edge"))
        && peekAt(1).type() == DotTokenType.LBRACKET) {
      parseDefaultAttrStatement(scope, token);
      return;
    }

    DotToken idToken = expectId("expected an identifier");
    if (peek().type() == DotTokenType.EQUALS) {
      advance();
      DotToken valueToken = expectAttrValue();
      scope.graphAttributes.put(idToken.text(), valueToken.text());
      countStatement();
      return;
    }

    List<DotToken> nodeIds = new ArrayList<>();
    nodeIds.add(idToken);
    rejectPort();
    while (peek().type() == DotTokenType.COMMA) {
      advance();
      DotToken nodeId = expectId("expected a node identifier after ','");
      nodeIds.add(nodeId);
      rejectPort();
    }

    if (nodeIds.size() > 1) {
      Map<String, String> explicit =
          peek().type() == DotTokenType.LBRACKET ? parseAttrList() : Map.of();
      Map<String, String> resolved = merge(scope.nodeDefaults, explicit);
      for (DotToken nodeId : nodeIds) {
        out.add(new DotNodeStatement(nodeId.text(), resolved, nodeId.location()));
        countStatement();
        countNodeIfNew(nodeId.text());
      }
      return;
    }

    String nodeId = idToken.text();

    if (peek().type() == DotTokenType.LBRACKET) {
      Map<String, String> explicit = parseAttrList();
      Map<String, String> resolved = merge(scope.nodeDefaults, explicit);
      out.add(new DotNodeStatement(nodeId, resolved, idToken.location()));
      countStatement();
      countNodeIfNew(nodeId);
      return;
    }
    if (peek().type() == DotTokenType.EDGE_OP) {
      List<String> chain = new ArrayList<>();
      chain.add(nodeId);
      // Count each hop as it is parsed, not after the chain is complete. One statement can carry
      // an arbitrarily long chain (`a -> a -> a -> ...`), so a ceiling checked only afterwards
      // would let a crafted document materialize far past MAX_ELEMENTS before tripping, bounded
      // only by the 64 MiB input ceiling. Same reason SourceValidator checks its element ceiling
      // inside the fragment-merge loop rather than on the merged result.
      countStatement();
      countNodeIfNew(nodeId);
      while (peek().type() == DotTokenType.EDGE_OP) {
        DotToken op = advance();
        if (op.text().equals("->") != directed) {
          throw DotLimits.syntax(
              directed ? "digraph edges use '->', not '--'" : "graph edges use '--', not '->'",
              op.line(),
              op.column());
        }
        DotToken endpointToken = peek();
        if (endpointToken.type() == DotTokenType.LBRACE) {
          throw DotLimits.unsupported(
              "subgraph-shorthand edges are not supported",
              endpointToken.line(),
              endpointToken.column());
        }
        if (endpointToken.isKeyword("subgraph")
            && (peekAt(1).type() == DotTokenType.ID || peekAt(1).type() == DotTokenType.LBRACE)) {
          throw DotLimits.unsupported(
              "a subgraph as an edge endpoint is not supported",
              endpointToken.line(),
              endpointToken.column());
        }
        DotToken endpoint = expectId("expected a node after the edge operator");
        chain.add(endpoint.text());
        rejectPort();
        countNodeIfNew(endpoint.text());
        countEdge();
      }
      Map<String, String> explicit =
          peek().type() == DotTokenType.LBRACKET ? parseAttrList() : Map.of();
      Map<String, String> resolved = merge(scope.edgeDefaults, explicit);
      out.add(new DotEdgeStatement(List.copyOf(chain), resolved, idToken.location()));
      return;
    }

    out.add(new DotNodeStatement(nodeId, Map.copyOf(scope.nodeDefaults), idToken.location()));
    countStatement();
    countNodeIfNew(nodeId);
  }

  private void parseDefaultAttrStatement(Scope scope, DotToken keyword) throws EngineException {
    advance(); // the graph/node/edge keyword
    Map<String, String> attrs = parseAttrList();
    if (keyword.isKeyword("node")) {
      scope.nodeDefaults.putAll(attrs);
    } else if (keyword.isKeyword("edge")) {
      scope.edgeDefaults.putAll(attrs);
    } else {
      scope.graphAttributes.putAll(attrs);
    }
    countStatement();
  }

  private DotSubgraphStatement parseSubgraph(Scope scope, int depth) throws EngineException {
    DotToken keyword = advance(); // 'subgraph'
    String id = null;
    if (peek().type() == DotTokenType.ID) {
      id = expectId("expected a subgraph identifier").text();
    }
    if (depth >= DotLimits.MAX_NESTING) {
      throw DotLimits.limit(
          DiagnosticCode.DOT_NESTING_LIMIT_EXCEEDED,
          "DOT source exceeds the 256 nested subgraph ceiling");
    }
    expect(DotTokenType.LBRACE, "expected '{' after subgraph");
    Scope child = scope.child();
    List<DotStatement> body = parseStatementList(child, depth + 1);
    expect(DotTokenType.RBRACE, "expected '}' to close subgraph");

    if (peek().type() == DotTokenType.EDGE_OP) {
      DotToken op = peek();
      throw DotLimits.unsupported(
          "a subgraph as an edge endpoint is not supported", op.line(), op.column());
    }

    countStatement();
    countElement();
    boolean cluster = id != null && id.startsWith("cluster_");
    return new DotSubgraphStatement(
        id, cluster, Map.copyOf(child.graphAttributes), body, keyword.location());
  }

  private void rejectPort() throws EngineException {
    if (peek().type() == DotTokenType.COLON) {
      DotToken colon = peek();
      throw DotLimits.unsupported(
          "ports and compass points are not supported", colon.line(), colon.column());
    }
  }

  private Map<String, String> parseAttrList() throws EngineException {
    Map<String, String> attrs = new LinkedHashMap<>();
    if (peek().type() != DotTokenType.LBRACKET) {
      DotToken token = peek();
      throw DotLimits.syntax("expected '['", token.line(), token.column());
    }
    while (peek().type() == DotTokenType.LBRACKET) {
      advance();
      parseAList(attrs);
      expect(DotTokenType.RBRACKET, "expected ']'");
    }
    return attrs;
  }

  private void parseAList(Map<String, String> attrs) throws EngineException {
    while (peek().type() != DotTokenType.RBRACKET) {
      DotToken key = expectId("expected an attribute name");
      expect(DotTokenType.EQUALS, "expected '=' after attribute name");
      DotToken value = expectAttrValue();
      attrs.put(key.text(), value.text());
      while (peek().type() == DotTokenType.COMMA || peek().type() == DotTokenType.SEMI) {
        advance();
      }
    }
  }

  private DotToken expectAttrValue() throws EngineException {
    DotToken value = peek();
    if (value.type() == DotTokenType.ID && value.kind() == DotTokenKind.HTML) {
      throw DotLimits.unsupported(
          "HTML-like attribute values are not supported", value.line(), value.column());
    }
    return expectId("expected an attribute value");
  }

  private DotToken expectId(String message) throws EngineException {
    DotToken token = peek();
    if (token.type() != DotTokenType.ID) {
      throw DotLimits.syntax(message, token.line(), token.column());
    }
    if (isReservedIdentifier(token)) {
      throw DotLimits.syntax(
          "reserved word '" + token.text() + "' cannot be used as an identifier",
          token.line(),
          token.column());
    }
    return advance();
  }

  private static boolean isReservedIdentifier(DotToken token) {
    return token.isKeyword("strict")
        || token.isKeyword("graph")
        || token.isKeyword("digraph")
        || token.isKeyword("subgraph")
        || token.isKeyword("node")
        || token.isKeyword("edge");
  }

  private void expect(DotTokenType type, String message) throws EngineException {
    DotToken token = peek();
    if (token.type() != type) {
      throw DotLimits.syntax(message, token.line(), token.column());
    }
    advance();
  }

  private void countStatement() throws EngineException {
    statements++;
    if (statements > DotLimits.MAX_STATEMENTS) {
      throw DotLimits.limit(
          DiagnosticCode.DOT_STATEMENT_LIMIT_EXCEEDED,
          "DOT source exceeds the 200000 statement ceiling");
    }
  }

  /**
   * Counts a node the first time its id is declared, whether by a node_stmt or an edge endpoint.
   */
  private void countNodeIfNew(String id) throws EngineException {
    if (declaredNodeIds.add(id)) {
      countElement();
    }
  }

  /** Counts an edge (each adjacent pair in a chain) or a subgraph; both always count. */
  private void countEdge() throws EngineException {
    countElement();
  }

  private void countElement() throws EngineException {
    elements++;
    if (elements > DotLimits.MAX_ELEMENTS) {
      throw DotLimits.limit(
          DiagnosticCode.DOT_ELEMENT_LIMIT_EXCEEDED,
          "DOT source exceeds the 100000 produced element ceiling");
    }
  }

  private static Map<String, String> merge(
      Map<String, String> defaults, Map<String, String> explicit) {
    Map<String, String> merged = new LinkedHashMap<>(defaults);
    merged.putAll(explicit);
    return Map.copyOf(merged);
  }

  private DotToken peek() {
    return tokens.get(index);
  }

  private DotToken peekAt(int offset) {
    int at = Math.min(index + offset, tokens.size() - 1);
    return tokens.get(at);
  }

  private DotToken advance() {
    DotToken token = tokens.get(index);
    if (index < tokens.size() - 1) {
      index++;
    }
    return token;
  }

  /**
   * The {@code node}/{@code edge}/{@code graph} defaults active at one point in the parse.
   * Subgraphs inherit a snapshot of their parent's defaults at open time; mutating a child's copy
   * never reaches back into the parent.
   */
  private static final class Scope {
    private final Map<String, String> nodeDefaults;
    private final Map<String, String> edgeDefaults;
    private final Map<String, String> graphAttributes = new LinkedHashMap<>();

    private Scope(Map<String, String> nodeDefaults, Map<String, String> edgeDefaults) {
      this.nodeDefaults = nodeDefaults;
      this.edgeDefaults = edgeDefaults;
    }

    static Scope root() {
      return new Scope(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    Scope child() {
      return new Scope(new LinkedHashMap<>(nodeDefaults), new LinkedHashMap<>(edgeDefaults));
    }
  }
}
