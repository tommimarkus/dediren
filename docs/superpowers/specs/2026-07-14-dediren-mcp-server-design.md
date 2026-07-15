# Dediren MCP Server Design (Plan C, decomposed)

## Status

Approved design anchor for the MCP surface of "Plan C — serve/MCP +
content-addressed cache + watch", the second follow-up to the Monolithic
Compiler Restructure (`2026-07-08-monolithic-compiler.md`, Phase 1, released
`2026.07.14`), after Plan B — typed IR (released `2026.07.15`).

This spec **decomposes** the Plan C stub. Plan C bundled three independent
subsystems: the `serve`/MCP surface, a content-addressed build cache, and a
`watch` mode. Only the MCP surface is designed here. The cache and `watch`
remain deferred stubs, unplanned and unscheduled.

The implementation plan is authored separately after this spec is approved.

## Purpose

Make Dediren a first-class tool surface for coding agents: an MCP stdio server
exposing `validate`, `build`, and an on-demand authoring guide as typed tools,
so an agent can author and compile a model without shelling out to a CLI and
without preloading `docs/agent-usage.md` into its context.

The motivation is **integration ergonomics, not speed**. Speed arrives as a
side effect and is not a design driver — see `## The Speed Question`.

## Decisions (resolved in brainstorming)

1. **MCP surface only.** The content-addressed cache and `watch` mode are
   *not* in scope. They answer to a different motivation (a live authoring
   loop) and carry their own costs (cache invalidation, a hashing contract, a
   stale-artifact failure mode). Deferred, not designed.
2. **stdio transport only.** No port, no HTTP/SSE listener, no network
   surface, no multi-client daemon. The MCP client spawns the server and owns
   its lifetime.
3. **Curated tool surface, plus a docs tool.** Three tools:
   `dediren_validate`, `dediren_build`, `dediren_guide`. The per-stage CLI
   commands (`project`, `layout`, `validate-layout`, `render`, `export`)
   remain CLI-only; they are the decomposed debug form and do not belong in an
   agent's always-loaded tool list.
4. **Paths in, paths out.** Tools take filesystem paths and write artifacts to
   disk, mirroring the CLI. Artifact content is never marshalled inline
   through the tool result. The server stays a thin shell over the existing
   `build` driver.
5. **Sectioned guide.** `dediren_guide` serves `agent-usage.md` by topic from a
   curated topic map, not as one 844-line blob.
6. **Workspace-root confinement, plus a read-only mode.** Every path argument
   must resolve inside a root the server is launched with.
7. **Official MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp` 2.0.0), not a
   hand-rolled JSON-RPC implementation. See `## Costs And Reversals` for the
   dependency cost this accepts and why.

## The Speed Question

Plan C framed `serve` as a performance play ("warm rebuilds ~50–150 ms").
Measured on the released `2026.07.16` bundle (warm CDS, Linux, dev machine):

| Lane | Cold, per invocation |
|---|---|
| `dediren validate` | ~120 ms |
| `dediren build` (`valid-pipeline-rich.json`: project → ELK layout → SVG render) | ~390–450 ms |

So the JVM boot floor is ~120 ms and pipeline work is ~270 ms. A resident
process saves the boot, and JIT warms across calls — real, but a ~250–300 ms
per-call prize, not the order-of-magnitude story the Plan C framing implies.

This matters as a *design constraint*: the speed win is not worth a new trust
boundary on its own. It is worth taking **for free** as a consequence of the
integration surface, because the MCP client keeps the server process alive
across calls. We therefore do not design for it: no cache, no warm-path
special-casing, no incremental re-layout. The server calls the same `build`
driver the CLI calls, and simply happens not to pay boot twice.

## Module Shape

A new tier-3 `mcp` module.

| Module | May compile-depend on | Stability tier |
|---|---|---|
| `mcp` | `contracts`, `core`, `engine-api`, `ir` | 3 — protocol adapter |
| `cli` | *(existing)* + `mcp` | 3 — entrypoint + wiring |

`mcp` holds the protocol wiring, tool definitions, tool handlers, path
confinement, and the guide topic map. It **receives an `Engines` registry
through its constructor** and never names an engine implementation class.

`cli` gains one thin `McpCommand` subcommand that calls the existing
`EngineWiring.defaults()` and hands the wired registry to the server.

This placement is what keeps the existing architecture rules intact:

- **"Only `cli` `EngineWiring` constructs engines"** — unchanged. `mcp` has no
  engine-implementation edge, so the ArchUnit rule pinning that edge to a
  single named class needs no exception.
- **"Keep `cli` thin"** — unchanged. `McpCommand` parses flags and delegates.
  Protocol handling, tool schemas, path confinement, and guide sectioning are
  real logic and get their own module rather than being smuggled into `cli`.
- **`dist-tool` needs no assembly change** — it bundles through `cli`'s compile
  deps, so `mcp` and the SDK jars arrive in the bundle transitively.
- The MCP SDK dependency lands in `mcp` only. Nothing below tier 3 ever sees
  Project Reactor.

## Lifecycle

`dediren mcp [--root <dir>] [--read-only]` — an stdio server.

The MCP client spawns the process, speaks JSON-RPC over its stdin/stdout, and
reaps it on exit. There is **no daemon to supervise**: no PID file, no socket,
no idle timeout, no orphan cleanup, no concurrent-client arbitration.

This is why the "requires its own lifecycle/threat-model design" caveat that
deferred Plan C largely dissolves. The lifecycle belongs to the client. What
remains genuinely new is the trust boundary, designed below.

## Tool Contracts

All three tools return the **existing envelope JSON verbatim** as the tool
result's text content. The MCP layer introduces no second result format. The
established agent contract — *decide success or failure from the JSON alone* —
is already documented, tested, and known to agents.

Tools additionally set MCP's native `isError` flag when the envelope status is
`error`, so clients get both the structured envelope and the protocol-level
error signal.

### `dediren_validate`

| Arg | Type | Required | Notes |
|---|---|---|---|
| `source` | path | yes | Source JSON. |
| `profile` | string | no | When present, runs semantic profile validation. |

Returns the validation envelope. When `profile` is supplied, the semantics
engine wire id (`generic-graph`) is applied by default; that id is a public
contract string, like a schema id, not a class reference.

### `dediren_build`

| Arg | Type | Required | Notes |
|---|---|---|---|
| `source` | path | yes | Source JSON. |
| `out` | path | yes | Output directory. |
| `views` | string[] | no | Defaults to all views. |
| `render_policy` | path | no | |
| `oef_policy` | path | no | |
| `xmi_policy` | path | no | |
| `emit` | string[] | no | `layout-request` \| `layout-result` \| `render-metadata`. |

Returns the build-result envelope, which already names every artifact written.
That is how the calling agent locates its SVG/OEF/XMI outputs.

### `dediren_guide`

| Arg | Type | Required | Notes |
|---|---|---|---|
| `topic` | string | no | Omitted → a short index of available topics. |

Curated topic map — the authoritative list is `GuideCatalog.TOPICS`, pinned
bidirectionally against `docs/agent-usage.md`'s `##` headings by
`GuideCatalogTest`. As shipped it covers: `source-json`, `build`, `commands`,
`render-policy`, `export`, `archimate`, `uml-sequence`, `uml-state-machine`,
`uml-use-case`, `uml-component`, `uml-deployment`, `profiles`, `artifacts`,
`fast-path`, `smoke`, `runtime-probes`, `environment`, `logging`, `migration`,
`repair`, and `mcp`.

A test pins the map against `docs/agent-usage.md`'s `##` headings **in both
directions**: every topic resolves to a real heading, and every heading is
reachable from at least one topic — so a newly added doc section cannot go
silently unreachable.

The guide **ships as a classpath resource in the `mcp` jar**, copied from
`docs/agent-usage.md` at build time by `maven-resources-plugin`. This removes a
runtime filesystem read and the entire `DEDIREN_PRODUCT_ROOT_UNRESOLVED`
failure lane from the guide tool, and the build-time copy means the shipped
guide cannot drift from the repository's.

Rationale for sectioning: `agent-usage.md` is 844 lines (~12–15k tokens).
Returning it whole would defeat the token-efficiency the document is explicitly
designed for. Sectioning is progressive disclosure — the agent pulls the part
it needs.

## Trust Boundary

This adds a genuinely new trust boundary: **a long-lived, model-driven process
holding a filesystem write primitive**. MCP clients frequently auto-approve
tool calls, so `dediren_build --out /etc` is a call a model can make
unsupervised. The CLI's posture ("a human typed this path") does not transfer.

### Control 1 — workspace-root confinement

The server is launched with `--root <dir>` (default: process cwd). Every path
argument is resolved against that root and then **`toRealPath()`-resolved
before the `startsWith(root)` check**.

Normalization alone is insufficient: a symlink *inside* the root pointing
outside it is precisely the interesting attack, and only real-path resolution
catches it. For `out`, which need not exist yet, the nearest existing ancestor
is real-path-resolved instead.

An escaping path yields a structured error envelope carrying a new
`DEDIREN_MCP_PATH_OUTSIDE_ROOT` diagnostic.

The CLI remains unconfined. This is intentional and is not an inconsistency:
a human typing a path is a different threat from a model emitting one.

### Control 2 — `--read-only`

Under `--read-only`, `dediren_build` is **not registered at all**. An absent
tool is a better contract than a tool that exists and refuses: the model never
sees a capability it cannot use, and it costs nothing in the client's context
window. `tools/list` returns two tools.

### Control 3 — stdout integrity

In stdio MCP, **stdout is the JSON-RPC channel**. A single stray
`System.out.println` anywhere in `core`, an engine, or a transitive dependency
corrupts a protocol frame, and the failure mode is the client silently going
dark — no error surfaces, the tools simply stop working. This is the single
most likely way to ship a subtly broken server.

Measured on the current runtime: the SLF4J "no providers" notice and the JVM's
CDS warnings both already go to stderr, so they are harmless today. That is
luck, not a guarantee, and it will not survive the next dependency.

The control: at server start, capture the real stdout handle for the transport,
then `System.setOut(...)` a stream redirecting to **stderr** for the remainder
of the process. Stray prints degrade to harmless log noise instead of protocol
corruption.

### Accepted residual — TOCTOU

Real-path-resolve-then-open is not atomic. A local attacker able to create
symlinks inside the root during the resolution window can defeat Control 1.
**Accepted.** The server already runs with the spawning user's authority, so
this grants an attacker nothing they did not already have. Control 1 exists to
stop a *model* writing outside the workspace, not to contain a hostile local
user. Recorded in `docs/threat-model.md` rather than papered over.

## Invariants And Testing

The load-bearing test is **CLI/MCP parity**: identical source and policies
driven through `dediren build` and through `dediren_build` must produce
byte-identical envelopes. This is the anti-drift guarantee — what prevents the
MCP surface quietly becoming a second, subtly different product.

Around it:

- **Path confinement** (`mcp` unit): traversal (`../../etc`), symlink escape
  from inside the root, non-existent `out` ancestor, absolute-inside-root,
  relative-inside-root.
- **Guide topic map** (`mcp` unit): both directions against `agent-usage.md`'s
  headings.
- **`--read-only`** (`mcp` unit): `tools/list` omits `dediren_build`.
- **Envelope → `isError` mapping** (`mcp` unit).
- **stdout purity** (`mcp` unit): an engine that writes to `System.out`
  mid-call must not corrupt a frame.
- **Protocol integration**: the SDK's `mcp-test` in-memory client driving a
  real server (`initialize` → `tools/list` → `tools/call`) with real engines
  wired.
- **Distribution smoke** (`dist-tool`, `-Pdist-smoke`): spawn `bin/dediren mcp`
  as an actual subprocess, speak JSON-RPC to its stdin/stdout, run a real
  build. Unit tests cannot catch a broken classpath in the shipped bundle;
  this can.

### Audit gates

| Audit | Mode | Why |
|---|---|---|
| `test-quality-audit` | Deep | New entrypoint, new tests, parity guarantee. |
| `devsecops-audit` | **Deep** | A new trust boundary *and* a new transitive dependency tree. The CLAUDE.md table's default Quick pass is insufficient here. |

## Costs And Reversals

### Dependency cost (accepted)

`io.modelcontextprotocol.sdk:mcp` 2.0.0 resolves against the current bundle as:

| Transitive dep | Status |
|---|---|
| `tools.jackson` databind 3.0.3 | already shipped (3.2.1) — manage up |
| `com.networknt:json-schema-validator` 3.0.0 | already shipped (3.0.6) — manage up |
| `jackson-annotations` 2.20 | already shipped (2.22) |
| `slf4j-api` 2.0.16 | already shipped (2.0.17) |
| `jakarta.servlet-api` 6.1.0 | `provided` scope — **not** shipped |
| `io.projectreactor:reactor-core` 3.7.0 | **new — 1.8 MB** |

Net new weight: ~2.5 MB on a 14 MB bundle (~18%) — Reactor, reactive-streams,
and three SDK jars. The SDK's sync server is a facade over an async core; there
is no Reactor-free variant.

Accepted because: protocol version negotiation, capability handshakes, and
stdio framing are the parts of MCP that churn as the spec evolves and that fail
in maddening, silent ways when subtly wrong. Reactor is the foundation of
Spring, not an exotic supply-chain bet. And Reactor's class-loading cost is
paid once per server session and then amortized to nothing — the performance
objection is moot in exactly this lane.

**The reversal:** the alternative was a hand-rolled JSON-RPC subset
(`initialize`, `notifications/initialized`, `tools/list`, `tools/call`, `ping`;
~300–500 lines, zero new dependencies, parsing through the existing fuzz-tested
`JsonSupport` mapper). If the SDK becomes a liability — unmaintained, licence
change, or Reactor becomes unacceptable weight — that is the exit, and the
`mcp` module boundary is drawn so that swapping the protocol implementation
touches no tool handler and no consumer.

### Build risk (named, not discovered later)

Maven Enforcer's `dependencyConvergence` is enabled and the SDK **will** conflict
on the four coordinates above. All four resolve upward cleanly and get pinned in
the root `dependencyManagement`. Named here so it surfaces as a planned step
rather than a mystery build failure.

## Files That Move Together

Per CLAUDE.md, this change has an unusually wide blast radius:

- **`docs/threat-model.md`** — mandatory. New section for the MCP stdio
  boundary: confinement control, stdout-integrity control, accepted TOCTOU
  residual.
- **`docs/architecture-guidelines.md`** — §2 dependency-edge table gains the
  `mcp` row and the `cli → mcp` edge; §3 gains the module charter entry.
  ArchUnit rules updated to match ("if a guideline is worth stating, it is
  worth an ArchUnit test").
- **`README.md` + `docs/agent-usage.md`** — together, per the move-together
  rule. Both document registration:
  `claude mcp add dediren -- /path/to/bundle/bin/dediren mcp --root .`
  `agent-usage.md` also gains the `DEDIREN_MCP_PATH_OUTSIDE_ROOT` token, which
  `AgentUsageDocConsistencyTest` enforces exists in source.
- **`contracts`** — the new `DiagnosticCode` entry.
- **root `pom.xml`** — new module, `dependencyManagement` pins.

## Verification

```bash
./mvnw -pl mcp,cli -am test
./mvnw -pl dist-tool -am verify -Pdist-smoke
./mvnw -Pquality verify
```

Release, if shipped, is CalVer `2026.07.17` in its own commit with an annotated
`v2026.07.17` tag, per `## Versioning` in CLAUDE.md.

## Non-Goals

- No content-addressed build cache. *(Plan C remainder, still deferred.)*
- No `watch` mode. *(Plan C remainder, still deferred.)*
- No network transport: no HTTP, no SSE, no Streamable HTTP, no port.
- No multi-client or resident daemon semantics.
- No MCP *resources* or *prompts* surface in this slice. `dediren_guide` covers
  the documented need with a tool, which works in every client without the
  client having to surface anything. Resources remain an additive option later.
- No per-stage tools (`project`, `layout`, `render`, `export`). CLI-only.
- No schema changes. No new engine. No envelope format change.

## What This Revises

- `2026-07-08-monolithic-compiler.md §Execution Modes` describes
  `dediren serve` as "the daemon done right, plausibly an MCP server". This
  spec resolves "plausibly" to **yes, and only that**: an stdio MCP server,
  named `dediren mcp` rather than `dediren serve` — because it is not a daemon
  and should not be named like one.
- The same document's Plan C stub bundles serve + cache + watch. This spec
  decomposes it and designs only the first.

## Evidence Record

- Timings: released `2026.07.16` bundle, warm CDS, Linux
  7.1.3-200.fc44.x86_64. `validate` ~120 ms; `build` of
  `fixtures/source/valid-pipeline-rich.json` with
  `fixtures/render-policy/rich-svg.json` ~390–450 ms (3 runs each).
- SDK dependency tree: `mcp` / `mcp-core` / `mcp-json-jackson3` 2.0.0 POMs from
  Maven Central, 2026-07-14. Latest release is 2.0.0 (the `0.17.x` in the
  published quickstart is stale).
- stderr/stdout channel check: SLF4J provider notice and JVM CDS warnings both
  observed on stderr with stdout redirected to `/dev/null`.
