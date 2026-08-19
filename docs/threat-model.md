# Threat Model

This page enumerates Dediren's trust boundaries, the controls that guard
them, and the incident-response runbook, in one auditable place. It replaces
scattered code comments and design-spec asides as the standing reference for
security-posture questions (audit finding F8).

## Assets

- Released agent-bundle archives (`dediren-agent-bundle-*.tar.xz`).
- `main` branch and `v*` tag integrity.
- Schema cache content (downloaded OMG XMI / OEF ArchiMate schemas).
- The envelope JSON contract surface (stdin/stdout between agents and the
  CLI).

## Trust Boundaries

### Envelope JSON stdin/stdout (agents -> CLI)

Parsing goes through `contracts/src/main/java/dev/dediren/contracts/json/JsonSupport.java`'s
Jackson 3 (`tools.jackson`) `ObjectMapper`: snake_case properties, fail on
unknown properties, non-null-only output. Fuzz-regression targets pin the
failure contract: `JsonSupportFuzzTest` (`contracts`) and
`SchemaValidationFuzzTest` (`engines/uml-xmi-export`) assert only
`JacksonException` / `XmiValidationException` may escape parsing, running in
deterministic regression mode over checked-in seed corpora in CI.

Build artifacts additionally carry a provenance stamp (wave 2): core injects
an inert SVG `<metadata>` element / leading XML comment holding
product-generated JSON (canonical hashes, schema id, tool version, a
charset-constrained view id) after each engine has validated its own content —
on the single-model lane and, since the package model, on every
`build --package` diagram and export artifact (declared `layout` /
`render_metadata` JSON outputs stay unstamped, matching the single-model lane).
Every stamped value is product-generated or constrained and the JSON is
XML-escaped at injection: `<`/`>`/`&` in the SVG metadata text, and in the
XML-comment lane every `--` run is rewritten to `&#45;&#45;` so no id can close
the comment early — a deliberate one-way escape (`Provenance.stampXml`),
accepted because no consumer reads `view_id` back out of a stamp. The stamp
therefore introduces no untrusted verbatim path into any artifact; SVG output
remains inert (no script/style).

Plan B P5 added a post-parse validation layer on top of that Jackson
contract: `ir.LayoutIntentCodec.decode` rejects an unrecognized
layout-constraint `kind` (or a malformed gap encoding) on the
`layout-request` wire fail-closed, surfacing as the clean
`DEDIREN_ELK_INPUT_INVALID_JSON` / exit-3 error envelope in place of the
former fail-open silent-ignore in the deleted `SequenceLayoutConstraints`;
and `validate-layout`/`build` now run `ir.quality.SequenceInvariants` against
an agent-suppliable `LayoutResult`, folding any violation into the hard-error
lane as `DEDIREN_LAYOUT_SEQUENCE_INVARIANT_VIOLATED`. The `render` lane accepts
a `LayoutResult` and turns it into an SVG artifact but does not validate it — it
emits layout-quality diagnostics (non-finite geometry, duplicate ids, non-positive
extents) as warnings instead of rejecting, a deliberate warn-first decision with
hard rejection deferred to a later release-noted change; as a residual, a
non-finite layout result still renders (e.g. `width="Infinity"`) with a warning
attached. Normal tests separately assert that the supported generated SVG corpus
conforms to Dediren's SVG 2 subset. That test-only control does not change this
runtime trust boundary and is not arbitrary-input validation.

Input ceilings bound what this boundary ingests (core `SourceLimits`, enforced
through `BoundedReads` and `SourceValidator` on the CLI and MCP lanes alike):
64 MiB per model-supplied input file, 1000 fragments, and 100000 merged
elements, each failing as a clean `DEDIREN_INPUT_FILE_TOO_LARGE` /
`DEDIREN_SOURCE_FRAGMENT_LIMIT_EXCEEDED` /
`DEDIREN_SOURCE_ELEMENT_LIMIT_EXCEEDED` diagnostic instead of an OOM or a
wedged layout run. The resource-exhaustion row in the attacker-goals table
records what deliberately stays unbounded.

Mermaid and DOT import add attacker-controlled *text* grammars without adding a
runtime, network fetch, external resource load, or copied parser. (draw.io
import keeps those four properties but is not a text grammar: it adds an
attacker-controlled XML grammar and an attacker-controlled compressed payload,
so it has its own trust-boundary section below.) The native
iterative Mermaid parser assembles only bounded logical statements and treats
exact, label-only `<br>`, `<br/>`, and `<br />` spellings as newline data; every
other tag, attribute, URL/resource, interactive directive, unsupported edge
semantic, or ambiguous construct is rejected before mapping. The DOT parser
continues to reject HTML-like labels, ports, tables, images, and subgraph
shorthand, and treats its unquoted reserved words as syntax rather than IDs.
Failures are atomic. Each importer separately caps UTF-8 input at 64 MiB,
statements at 200000, produced nodes/relationships/groups at 100000, nesting at
256, and tokens (plus Mermaid labels) at 64 KiB. CLI stdin uses the same bounded
streaming read as file input. The MCP tool first confines and bounds its source
path, is available in read-only mode because it writes nothing, then returns the
core envelope.
Because an importer constructs a source model in memory rather than handing
over bytes the load lanes validate, `core` re-gates the document it emits —
model schema plus the `SourceLimits` ceilings — before publishing it; the
importer remains authoritative for its own parse diagnostics. See the
import row in the attacker-goals table.

### draw.io import (attacker-controlled XML + compressed page payloads)

`dediren import --plugin drawio`, and the `drawio` plugin on the MCP
`dediren_import` tool, is the only lane that takes attacker-supplied **XML as
diagram input** (the schema-path env overrides in the schema-cache boundary
below are operator configuration, not input). It adds no runtime, no network
fetch, no external resource load, and
no copied parser — the reader is first-party
(`engines/drawio/.../mx/MxReader.java`) over the JDK's StAX API — but a single
`.drawio` file presents *two* attacker-controlled documents, and one of them
arrives compressed.

**What the hardening actually does** (`engine-api/.../SecureXml.java`, one
implementation because engines may not depend on each other and a drifted XML
reader is an XXE, not a cosmetic inconsistency). Every call returns a fresh
`XMLInputFactory`, which is mutable and not documented as thread-safe, so a
shared instance could be reconfigured — including un-hardened — by any holder.

- `SUPPORT_DTD = false` does **not** reject a document carrying a `DOCTYPE`. On
  the JDK StAX implementation the declaration is parsed and reported as an inert
  `DTD` event: nothing external is fetched and no entity is declared. Refusal
  happens one step later, when the document *references* an entity the DTD would
  have declared — the parse then fails outright rather than being bounded the way
  the JDK's 64000-expansion limit bounds it. A document with an external DTD and
  no entity reference therefore parses cleanly. That is a recorded decision, not
  an oversight: `MxReaderTest.acceptsADoctypeThatReferencesNothing` documents that
  a DTD-event check was tried and reverted, because refusing the declaration up
  front means the parse never reaches the entity reference — the only observable
  that distinguishes a hardened inner factory from a default one — and would leave
  the "default factory on the inner parse only" mutant alive. Changing this posture
  changes that test and this page together.
- `FEATURE_SECURE_PROCESSING` is **not an active control here**. The JDK's own
  `XMLInputFactoryImpl` does not support it on `XMLInputFactory` and throws
  `IllegalArgumentException`, so it is set only behind an `isPropertySupported`
  guard and is a no-op on the shipped runtime. It is kept solely because
  `XMLInputFactory.newFactory()` performs a `ServiceLoader` lookup, so a
  third-party StAX provider on the classpath would be hardened too.
- The throwing `XMLResolver` is **not decoration**. It never fires in the shipped
  configuration, but mutation testing shows that with `SUPPORT_DTD` re-enabled it
  is what refuses the external DTD subset and external parameter entities. It is
  the real second line. `IS_SUPPORTING_EXTERNAL_ENTITIES` and
  `IS_REPLACING_ENTITY_REFERENCES` are the weakest members of the set —
  `SUPPORT_DTD = false` masks both, so no payload can tell whether they are set —
  and are layered defence only. `IS_COALESCING` is a functional requirement (a
  compressed page body must arrive as one `CHARACTERS` event), not a defensive one.
- No hardened `DocumentBuilderFactory` lives in `SecureXml`: nothing on a
  production path DOM-parses untrusted input, and an unused factory would be an
  untested attack surface.

**The decompressed payload is a second, fully independent document.** It gets its
own `SecureXml.inputFactory()`; parsing it with a default factory would switch off
every control the outer parse applies, for the half of the format a reviewer cannot
read. `MxReaderTest` pins that with a mutation proof — the same payload is shown to
be *accepted* by a default `XMLInputFactory` and refused by the hardened one, so the
refusal is demonstrably caused by the inner factory — plus a real exfiltration test
that writes a secret to disk, references it through an entity inside a compressed
page, and asserts the secret appears in neither the diagnostic message nor its path.

**Decompression is bounded during the inflate, not after it.** draw.io writes a
page as percent-encoded UTF-8 XML → raw DEFLATE → base64. `MxDeflate` raw-inflates
through a running byte counter and aborts inside the loop the moment the budget is
exhausted (`DEDIREN_DRAWIO_DECOMPRESSED_TOO_LARGE`), so at most one 64 KiB chunk
beyond the ceiling is ever held; inflating into an unbounded buffer and measuring
afterwards is the bomb going off before the alarm. The budget equals the 64 MiB
input ceiling and is a **running total across every page of the document**, not a
per-page allowance — compressed input therefore buys an attacker no more budget
than an uncompressed file, where a per-page cap of that size would admit 256 ×
64 MiB. Base64 text over the input ceiling is refused *before* being decoded into a
byte array (`DEDIREN_DRAWIO_INPUT_TOO_LARGE`). **There is deliberately no
compression-ratio cap**: raw DEFLATE tops out near 1032:1, so the absolute byte
ceiling already bounds the worst case, and a ratio cap would add a second tunable
and a second diagnostic code without bounding anything the byte ceiling does not.
Percent-decoding is hand-rolled `decodeURIComponent` semantics (`URLDecoder` maps
`+` to a space and would corrupt labels) and can only shrink the payload, so it
needs no budget of its own. Bad base64, a malformed DEFLATE stream, a truncated
stream, and a malformed percent escape are all `DEDIREN_DRAWIO_DECOMPRESSION_FAILED`.

**Which ceilings abort mid-parse, and which do not.** Pages (256), cells (200000,
counted across the whole document rather than per page for the same reason the
decompression budget is) and per-attribute bytes (64 KiB) are counted as the
streaming walk proceeds and abort it in place — which is why the reader streams
rather than materializing a tree first. `MxReaderTest` proves that for the page
ceiling by putting a syntax error after the 257th page and asserting the
page-limit code, which is only reachable if the count aborts before the reader
gets there. The attribute check also runs on elements the reader skips, because a
ceiling that depends on where in the tree an attacker puts the payload is not a
ceiling. **Nesting is the exception, and knowingly so**: containment in this
format is the `parent` attribute and a parent may be declared *after* its child,
so a cell's depth is not knowable at the moment the cell is read. Depth is
resolved in one memoized pass at the end of each page, over a cell set the cell
ceiling has already bounded — bounded, therefore, but not mid-parse; the same
pass refuses a cycle on the step that closes it and a parent no cell on the page
declares. Produced elements (100000) are the mapper's post-parse ceiling, held at
or below `SourceLimits.maxElements()` on purpose so an over-large document is
refused by the importer's own exit-2 `DEDIREN_DRAWIO_ELEMENT_LIMIT_EXCEEDED`
rather than misreported by core's exit-3 re-gate. Failures are atomic: the caller
receives a complete document or an exception, never a prefix.

**Content refusals are what keep "no external resource load" true** rather than
merely usually true. The mapper fails the whole import with
`DEDIREN_DRAWIO_UNSUPPORTED_CONSTRUCT` on a `link` or `linkTarget` attribute (the
format's one way of reaching outside the file); on `shape=image` or any `image`
style key, and on an `imageBackground` URL (every one of which is a resource
load); on an embedded `shape=stencil(...)` definition, which is a second nested
compressed payload Dediren refuses to decode at all; and on label HTML beyond the
exact `<br>`, `<br/>`, `<br />` family, identical to the Mermaid importer's rule.
Geometry, styling and routing are discarded outright — `schemas/model.schema.json`'s
`sourceNode` is `additionalProperties: false` with no coordinates, and every import
lane re-lays the page out with ELK — so no attacker-supplied number or style string
reaches layout or render.

**The round-trip property channel widens what an import can carry.** Dediren's own
export writes each page's element properties as JSON on a hidden `dediren.view`
metadata cell, so a re-import restores them and the pair reaches a byte-identical
fixed point. The cost is that a hostile `.drawio` can now inject arbitrary JSON
under **any** property namespace, where before an import could only produce
`drawio.*` keys and a `uml.sequence` ordering. Two bounds apply and both are
tested: `MxReader`'s 64 KiB per-attribute ceiling caps the blob, and Jackson's
500-level nesting guard caps its depth; a blob that is unparseable, over-deep, or
not an object fails the whole import atomically with
`DEDIREN_DRAWIO_ROUND_TRIP_INVALID`. What the channel does *not* do is grant the
imported properties any authority — they land in `SourceDocument` properties like
any other input and are re-gated by `SourceValidator.gateImportedDocument` and then
by the notation semantics, which reject a property they do not recognise. The
export side guards the same ceiling in the other direction: a page whose property
map or layout-preferences block would exceed it is written without that block and
reported with `DEDIREN_DRAWIO_PROPERTIES_DROPPED`, rather than producing a file
this build's own importer would refuse.

**Diagnostics and regression cover.** Attacker-supplied fragments echoed into a
published diagnostic are truncated to 80 characters, and the JDK's own XML error
text is never echoed because it quotes the document including any system
identifier. `DrawioImportFuzzTest` (Jazzer, deterministic regression over a
checked-in corpus that includes malformed XML, a DOCTYPE, a deeply-nested parent
chain, a compressed page, a truncated base64 payload and a cyclic parent chain)
pins the seam invariant: arbitrary bytes either yield a valid `SourceDocument` or
fail atomically at exit 2 with exactly one `DEDIREN_DRAWIO_*` diagnostic.
`SourceValidator.gateImportedDocument` re-gates this importer's output exactly as
it does the other two.

### Single-JVM engine runtime (no plugin execution surface)

The runtime is a single JVM with no plugin discovery or execution surface:
the bundled first-party engines (`mermaid`, `dot`, `drawio`, `generic-graph`,
`elk-layout`, `render`, `archimate-oef`, `uml-xmi`) are compile-time library
modules behind the
`engine-api` interfaces, constructed explicitly in one named cli class
(`cli/.../EngineWiring.java`) and dispatched in-process by `core`'s
`EngineDispatch` (`core/.../engine/EngineDispatch.java`). Core never resolves
an executable, spawns a child process, or reads a plugin path/trust
environment variable; an unknown engine id is answered from the in-memory
registry (`DEDIREN_PLUGIN_UNKNOWN`), not from any filesystem lookup. The
former manifest env allowlist is gone because engines spawn no child processes —
the export engines receive the CLI's env map explicitly (schema-path/cache and
HTTP-proxy variables) and do not call `System.getenv()` themselves, pinned by
the engines' no-`getenv` guard tests (Task 4).

### MCP stdio server (`dediren mcp`)

`dediren mcp` (module `mcp-server`, launched by `cli`'s `McpCommand`) is a
long-lived, model-driven process holding a filesystem write primitive
(`dediren_build`) alongside read primitives over source models and workspace
directories. It is the one boundary where a *model* — not a human — chooses the
paths, and MCP clients
frequently auto-approve tool calls, so the CLI's "a human typed this path"
posture does not transfer.

There is no network *listener*: stdio transport only, no port, no HTTP/SSE
listener, no multi-client daemon, and the MCP client spawns the process and owns
its lifetime, so there is no daemon lifecycle to supervise. That is inbound-only,
though: a `dediren_build` call whose policy selects the OEF or XMI export lane
reaches the same outbound-HTTPS boundary as the CLI's `build` and
`export` commands (see "Schema cache + runtime download" and "XML parsing &
schema validation" below) — absent a cached or offline schema, an in-JVM Java
HTTP fetch;
schema validation itself runs in-JVM on both lanes with no subprocess.
`--read-only` withholds `dediren_build` entirely. Short of that, the
`DEDIREN_OEF_SCHEMA_DIR` / `DEDIREN_XMI_SCHEMA_PATH` offline overrides remove
the outbound fetch.

Prompt-supplied Mermaid/DOT `content` is attacker-controlled text and uses the
same byte and parser ceilings as file input. Image responses are JSON-first:
the unchanged envelope is primary text and optional SVG/PNG attachments follow.
`output: "image"` negotiates only the declared `accepted_image_types`, with SVG
fixed ahead of PNG; no accepted type, unavailable/failed conversion, and invalid
input, policy, layout, or render paths remain JSON-only. Decoded attachments
share a cumulative 64 MiB limit. Clients may not support displaying either
media type, so inline display is not guaranteed.

Controls:

- **Server-root confinement.** Every tool path argument is resolved against the
  `--root` (default: cwd) and real-path-resolved *before* the containment check.
  The algorithm has exactly one implementation —
  `core/src/main/java/dev/dediren/core/io/ConfinedPaths.java` — which
  `mcp-server`'s `WorkspacePaths` adapts onto the sanitized MCP error surface
  (previously two hand-mirrored copies that had already diverged on
  normalization order). Normalization alone is
  insufficient — a symlink inside the root pointing outside is the interesting case,
  and only `toRealPath()` catches it; the path stays *unnormalized* until it is
  anchored on a real existing ancestor, so a `link/..` sequence is resolved
  physically, never collapsed lexically. For an output directory that need not
  exist, the nearest existing ancestor is resolved instead (the walk does not
  follow symlinks). An escaping path yields a
  `DEDIREN_MCP_PATH_OUTSIDE_ROOT` error envelope. Pinned by `WorkspacePathsTest`.
  Core-reported artifact paths are treated as untrusted data and re-confined to
  the server root (or package root for package outputs) before follow-up reads
  or image encoding; bounded reads still apply.
  The confinement also covers the *second* class of model-supplied paths: a source
  document's `fragments[]` entries. The MCP handlers pass `--root` as an optional
  confinement root into core's source loader (`SourceValidator`), which applies the
  same shared `ConfinedPaths` containment to each fragment before
  reading it (the CLI/human lane passes no root and is unconfined). An escaping
  fragment yields the same `DEDIREN_MCP_PATH_OUTSIDE_ROOT` envelope, and both the
  escape and any in-root read failure are sanitized to echo only the model's own
  relative fragment string — never the resolved absolute path and never a
  distinguishable exists-vs-not-exists signal. Reachable under `--read-only`,
  since `dediren_validate`, `dediren_diff`, `dediren_query`, `dediren_verify`,
  and `dediren_status` all load source models (and thus fragments). The
  read-only `dediren_verify` and `dediren_status` add a second read shape — a
  *directory* argument (`artifacts` / `dir`) — confined by the same
  `WorkspacePaths.resolveExisting` real-path check;
  their result envelopes report artifact paths relative to that directory, so no
  absolute server path is echoed back to the model. `dediren_status` in
  particular *enumerates* the whole confined subtree (every model and stamped
  artifact under `dir`, default the root) — a workspace-structure disclosure
  broader in shape than reading a single named path, but still bounded by the
  `--root` the model is already trusted to read within. Pinned by
  `DedirenToolsTest`, `SourceValidatorTest`, `AnalysisCommandsTest`, and
  `CliMcpParityTest`.
- **Read-only mode.** `--read-only` does not register `dediren_build`, so the
  write primitive is absent rather than present-and-refusing. The four analysis
  tools (`dediren_diff`, `dediren_query`, `dediren_verify`, `dediren_status`) are
  read-only and stay registered in both modes; `dediren_import` also stays
  registered because it only reads one confined source and returns data.
- **Optional PNG conversion.** PNG is MCP-owned response adaptation over a
  core-produced SVG, not a render-engine artifact. `--resvg-command` accepts
  only a bare `PATH` name or an absolute executable path and resolves it once at
  server startup; it accepts no command arguments and no shell is involved.
  The child inherits no environment values (`PATH` and `HOME` are set empty),
  receives SVG on stdin, and returns PNG on stdout. A conversion is bounded to
  15 seconds, 4096 by 4096 pixels, 64 KiB captured stderr, and the remaining
  share of the 64 MiB decoded-attachment ceiling. The adapter checks the PNG
  signature, IHDR shape, and dimensions before attachment. Timeout, process
  failure, malformed/oversized output, or no resolved executable degrades to
  the successful command's JSON envelope only. The executable is operator
  supplied, not bundled or listed in `THIRD-PARTY-NOTICES.md`; upstream `resvg`
  is licensed MIT OR Apache-2.0.
- **Package declared outputs.** `dediren build --package` (and `dediren_build` with
  a `package` argument) add a caller-*declared* write surface: each view and export
  names the path its artifact lands at. Every declared output path — and every
  declared input reference (`models[].source`, the render/export policies) — is
  resolved and confined with the same `ConfinedPaths` real-path check as the
  single-model lane. Inputs are relative to the package directory and confined
  to the server `--root`; outputs are relative to and confined within the
  package directory. The CLI/human lane uses the same package-relative output
  rule. An escaping path is a
  structured `DEDIREN_COMMAND_INPUT_INVALID` (CLI) / `DEDIREN_MCP_PATH_OUTSIDE_ROOT`
  (MCP) error, never a write, and colliding declared paths are rejected before any
  build begins. Package id spaces (`models[]`/`views[]`/`exports[]`) are
  schema-constrained to the shared id charset and duplicates are rejected up front
  (`DEDIREN_PACKAGE_DUPLICATE_ID`), so a hand-authored package cannot alias two
  build units onto one id to confuse output or export routing.
  Collision detection is lexical (normalized-path string compare),
  not real-path, so — as with the `ConfinedPaths` symlink residual above — a
  pre-existing symlink alias *inside* the confined tree could let two
  lexically-distinct declared paths resolve to the same file; that needs the same
  in-root filesystem control the accepted TOCTOU residual already assumes, and does
  not escape confinement. Pinned by `PackageBuildCommandTest`,
  `PackageValidatorTest`, `CliPackageBuildTest`, and `DedirenBuildPackageToolTest`.
- **Resources serve product bytes only.** The MCP resources surface
  (`dediren://schema/…`, `dediren://fixture/…`, `dediren://guide/…`,
  `dediren://diagnostics/catalog`) enumerates and reads exclusively under the
  product root (the bundle's own `schemas/`, `fixtures/`, and packaged guide) —
  never a workspace path, never a model-supplied path: `resources/read` resolves
  a URI against the startup-enumerated set, so there is no path parameter to
  confine and no new read primitive into the workspace. Served identically under
  `--read-only`.
- **stdout integrity.** In stdio MCP, stdout *is* the JSON-RPC channel; a stray
  `System.out` write anywhere in core, an engine, or a dependency would corrupt a
  frame and the client would silently go dark. `StdoutIntegrity.claimStdout()` takes
  the real file descriptor for the transport and redirects `System.out` to stderr, so
  a stray print degrades to log noise. Pinned by the dist-smoke assertion
  `assertMcpServesToolsOverStdio`, which requires every stdout line to be a JSON-RPC
  frame.

Accepted residual — **TOCTOU**: real-path-resolve-then-open is not atomic, so a local
attacker able to create symlinks inside the root during the window can defeat the
confinement. Accepted: the server runs with the spawning user's authority, so this
grants an attacker nothing they did not already have. The confinement exists to stop
a *model* writing outside the workspace, not to contain a hostile local user.

### Schema cache + runtime download

Runtime schema fetches go through
`schema-cache/src/main/java/dev/dediren/schemacache/SchemaCacheModule.java`'s
Java `HttpClient`. The initial request and final response URI must both use
HTTPS; `Redirect.NORMAL` follows safe redirects but refuses an HTTPS-to-HTTP
downgrade. Connects are bounded at 20 seconds, each request at 60 seconds, and
streaming response bodies at 8 MiB. A non-2xx status or limit failure becomes a
structured fetch failure, and a temporary download never replaces the old
cache entry until its SHA-256 matches the pinned value:
the single `OMG_XMI_SCHEMA_SHA256` constant
(`engines/uml-xmi-export/.../schema/SchemaValidation.java`) and the per-file
`PINNED_OEF_SCHEMA_SET` table (`engines/archimate-oef-export/.../OefExportEngine.java`),
which pins the three Open Group OEF XSDs (opengroup.org) plus the W3C
`xml.xsd` they import (www.w3.org — the one non-standards-body-of-origin
endpoint in the fetch set; the in-JVM validator resolves the import from the
local copy, never from the network).
The explicit proxy selector checks `HTTPS_PROXY`, `HTTP_PROXY`, then
`ALL_PROXY`, with lowercase taking precedence within each name. `NO_PROXY`
supports exact hosts, leading-dot suffixes, and `*`. A selected proxy must be a
credential-free `http` URI with a host and no path/query/fragment; secure proxy
transport and proxy-URI credentials are not supported. Invalid configuration
fails closed with a credential-free error rather than silently connecting
directly.
The offline overrides `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` bypass the
SHA-256 check by design — they only require the supplied file to be non-empty.

### XML parsing & schema validation

This section covers XML the product **generated itself** and then re-reads to
validate. Attacker-supplied XML arriving as *input* is a different trust context
with a different factory — see the draw.io import boundary above, whose reader
uses the hardened StAX `SecureXml.inputFactory()`; the DOM factory below is never
pointed at untrusted input.

Generated UML/XMI XML is parsed with a hardened `DocumentBuilderFactory`
(`SchemaValidation.secureXmiDocumentBuilderFactory()`): DOCTYPE declarations
disallowed, `FEATURE_SECURE_PROCESSING` on, XInclude and entity-reference
expansion off. The whole-model `model.uml.xml` lane appends OMG UMLDI diagram
interchange (`umldi:`/`di:`/`dc:` shapes and waypoints derived from the ELK
layout) as siblings of `<uml:Model>`; it flows through the same hardened parser,
DOM `xmi:id` uniqueness check, and the same in-JVM XMI schema validation, whose
tolerated no-declaration gap now also covers those DI namespaces (no normative
OMG DI XSD is published) — no new fetch, subprocess, or parser surface.

Successful UML/XMI envelopes expose a separate machine-readable assurance
object (`uml-xmi-assurance.schema.v1`). Its current validation level is bounded
to `xmi-envelope-only`, with empty UML-metamodel and importer evidence, so the
tolerated no-declaration gap cannot be mistaken for UML-content conformance.
Schema conditionals require evidence before a stronger future level can be
claimed. The assurance coverage counts are derived from the source IDs the
writer actually emitted; they are reporting data, not a new input or trust
boundary.

Standards-schema validation runs **in-JVM on both export lanes** via the one
shared validator, `schemacache.InJvmXmlValidator` (engines may not depend on
each other, so hardening lives once): `javax.xml.validation` with secure
processing on, external DTD/schema access denied, and schema imports (the W3C
`xml.xsd` the ArchiMate XSDs reference; a UML XSD beside an XMI driver schema)
resolved **local-only** from the schema file's own directory, confinement
enforced by a normalize/startsWith check — nothing is fetched at validation
time and no validator subprocess exists anywhere in the product. The retired
`xmllint` lane's guards carry over in in-process form:

- **Bounded wall clock.** Compile+validate run on a bounded daemon worker;
  exceeding `InJvmXmlValidator.DEFAULT_TIMEOUT` (60s) is reported as a
  structured `DEDIREN_OEF_SCHEMA_UNAVAILABLE` / `DEDIREN_XMI_SCHEMA_UNAVAILABLE`
  rather than an indefinite stall (a pathological schema in a hand-supplied
  directory cannot hang the envelope; the abandoned worker thread ends with the
  process, since an in-process computation cannot be force-killed the way a
  subprocess could). Because such a worker cannot be reclaimed, a concurrency
  gate (`InJvmXmlValidator.MAX_CONCURRENT_VALIDATIONS`) bounds how many run at
  once: a submission that would exceed it fails fast to the same
  `*_SCHEMA_UNAVAILABLE` lane, so repeated timeouts degrade to unavailability
  instead of leaking worker threads without limit.
- **Broken-validator vs invalid-document split.** Schema-set problems
  (unresolved import, unreadable file, validator configuration failure,
  timeout) throw to the engines' `*_SCHEMA_UNAVAILABLE` lane; only genuine
  document-validity findings become `*_SCHEMA_INVALID`, so an environment
  problem is never misreported as a defect in the generated XML. The thrown
  `SchemaCacheException` carries a failure `Kind` (schema-set / config /
  timeout / saturated / fetch) and the engines append class-appropriate advice:
  schema-placement/proxy remediation only for a missing or broken schema set,
  "transient, retry" for saturation — so an agent is never told to reconfigure
  a proxy for a capacity blip.
- **Compiled-grammar reuse.** A compiled grammar is memoized per top-file path
  and served only while every file that shaped it — the top schema and the
  imports it resolved — still matches its (size, mtime) stamp. Each stamp is
  captured when the file is *read* (top schema before compile, each import
  before its bytes are served), never after the compile, so a file rewritten
  mid-compile can only trigger an extra recompile on the next call — it can
  never pin a stale grammar as fresh; the pinned cache-lane contents are
  additionally SHA-256-verified at fetch time.

The validator returns a code-free outcome the calling engine maps onto its own
published diagnostic codes, so `schema-cache` stays notation-neutral, and every
successful export declares what it was validated against via the
`DEDIREN_EXPORT_SCHEMA_CONFORMANCE` info diagnostic. `InJvmXmlValidatorTest`
pins the seam: local-only resolution and traversal confinement, the
unavailable-vs-invalid split, single-recording of fatal errors, dependency-aware
memoization, the bounded-run timeout, and the saturation fail-fast. Schema
fetching now stays in-JVM; the only optional product child process is the MCP
adapter's bounded `resvg` conversion described above.

### SVG output escaping

Untrusted model text (node/edge/group labels and ids) flows into the SVG
render surface. Every value is XML-escaped at emission by `SvgWriter`
(`engines/render/.../svg/SvgWriter.java`), the engine's StAX-backed emitter:
element content goes through `text()` and attribute values through
`attr()`/`attrIf()`, which escape structurally. Escaping is therefore a
property of the writer, not a call-site invariant a caller can forget — the
hand-rolled string emitter and its `Svg.attr`/`Svg.text` helpers are gone, and
there is no verbatim-injection path. So a label such as
`</text><script>alert(1)</script>` reaches the output only in escaped form and
cannot break out of its host element. `LabelInjectionTest` (`engines/render`)
drives a breakout payload through a full render and asserts both that the
payload never appears unescaped and that the label round-trips back to the exact
authored string; `SvgAudit` re-parses the emitted SVG and fails on any
ill-formed markup or violation of the independently defined SVG 2 subset. The
same JDK-only assertion runs after provenance stamping and on real package and
CLI build artifacts. Its XML parser rejects DTDs and external entities and
permits no external schema access; it performs no network call and introduces
no shipped dependency or runtime parser surface. The rendered SVG carries no
`<script>` or `<style>` block at all — it is inert, fully escaped markup
(interactive-svg was retired), so there is no CSS/script sink for policy values
or model text to reach. The assertion covers only the supported generation
corpus; it is not full SVG 2 certification, arbitrary-input proof, or runtime
validation.

`accessibility.lang`/`.dir` — reaching the root as `xml:lang`/`direction`, either
from the render policy directly or folded in from a package's `presentation` —
are authored strings on that same escaped path: both go out through `attrIf()`,
so the writer-level property above covers them and no new verbatim sink is
introduced. On the package lane, they are additionally bounded before emission
by the schema: `package.schema.json` constrains `dir` to a two-value enum and
`lang` to a length-capped subtag pattern (`PackageBuildCommand.java:644-648`).
On the render and build lanes, the schema validation does not apply
(`CoreCommands.parsePolicy` runs only a schema-version gate; `RenderInputValidator.validateRenderPolicy`
never visits `accessibility`), so only escaping guards these values before
reaching the attributes. Both are omitted when unset, so an untagged policy
produces the identical root it did before the keys existed.

The `render` plugin emits SVG as text and no longer bundles Apache Batik /
XML Graphics. Earlier versions round-tripped the emitted SVG through a Batik
SVG DOM and PNG transcoder to produce a `png` artifact, re-parsing the
rendered markup (and its CSS/font references) a second time inside the plugin.
That path is removed: the plugin never re-parses its own output and carries no
Batik XML-parsing surface. The ordinary CLI still requires a user-chosen
external converter for a PNG artifact. Separately, the MCP protocol adapter may
invoke only its startup-resolved, bounded `resvg` executable to adapt a valid
SVG into an optional response attachment; core and the render engine remain
unaware of PNG.

### Build, documentation publication & release chain

`.github/workflows/release.yml`, `ci.yml`, and `pages.yml` pin every GitHub
Action to a commit SHA; the Maven Wrapper is SHA-256-pinned in
`.mvn/wrapper/maven-wrapper.properties`. Every pull request and tagged release
runs a blocking Grype/SBOM scan (`anchore/scan-action`, `fail-build: true`,
`severity-cutoff: high`); direct pushes to `main` carry no CI (lean-CI
decision) — except that relevant self-model documentation changes run the Pages
build — and rely on the local verification lanes before the next pull request
or release gate. The release `build` job generates
one provenance attestation covering the archive, both CycloneDX SBOM
serializations, **and** the `SHA256SUMS` checksum file
(`attest-build-provenance`), so the SBOM and checksums are bound to the attested
build rather than regenerated downstream; the `publish` job verifies every
attested subject (`gh attestation verify`), checks the staged assets against the
attested `SHA256SUMS` (`sha256sum -c`), and then creates the release as a
draft with all assets attached before flipping it to published. The attested
`build` job restores no cross-run cache: every dependency cold-resolves from
Maven Central under Maven's strict-checksums flag, so the provenance's input
set is the tagged commit plus checksum-verified downloads. Repository
release immutability is enabled, so publishing freezes the complete, attested
asset set (releases published before 2026-07-23 predate the setting and remain
mutable). Repository rulesets (declarative source: `.github/rulesets/`) block
force-pushes and deletion of `main` and moving or deleting `v*` tags, and
require the `test`/`vulnerability-scan` checks before `main` moves — with a
recorded repository-admin bypass preserving the solo direct-push workflow. The
absence of required review on `main` remains a documented accepted risk — see
`SECURITY.md`.

The Pages workflow is a separate documentation-publication boundary for only
the checked-in `docs/architecture/dediren.dediren/` self-model package. Its
build runs for relevant pull requests, but pull-request runs never deploy. Only
push-to-`main` and manual non-pull-request runs deploy through the
`github-pages` environment, using the Pages deployment token (`pages: write`)
and OIDC (`id-token: write`). The build job has only `contents: read`; checkout
does not persist credentials, and neither job uses a PAT or receives
`contents: write`. The source SVG is inert/escaped, while GitHub Pages, Jekyll,
and the SHA-pinned official GitHub Actions remain trusted build and deployment
services for this boundary.

The bundle `lib/` jar is produced by a shrink ProGuard pass over the staged
launcher classpath (`dist-tool`, keep rules checked in at
`dist-tool/src/main/resources/dev/dediren/tools/dist/bundle-shrink.pro`); the
pass does no optimization and no renaming — ProGuard's obfuscation phase runs
only as an attribute filter with every class and member name pinned.
ProGuard is a pinned, SBOM-scanned build-time dependency and never ships. The
`.tar.xz` archive is compressed by the build machine's distro `xz` (a
standard, distro-verified package on the runner; consumers extract with
their own `tar`/`xz` or libarchive). The `-Pdist-smoke` gate exercises every
pipeline against the packaged shrunk archive, and the smoke's archive-size
ceiling trips if shrinking or attribute stripping silently degrades.

## Attacker Goals -> Controls

| Attacker goal | Primary control | Residual risk |
| --- | --- | --- |
| Poison a release artifact | SHA-pinned Actions, blocking Grype/SBOM gate, attestation generated and verified before publish | Single-maintainer `main` has no required review (accepted risk, `SECURITY.md`) |
| Publish a malicious self-model documentation site | Pages builds relevant pull requests without deployment; only push/manual non-PR runs deploy through `github-pages` with scoped `pages: write` and OIDC, no persisted checkout credentials, PAT, or contents write | GitHub Pages, Jekyll, and the SHA-pinned official actions are trusted services; a malicious change merged to `main` can publish the self-model package |
| Tamper `main` or `v*` tags | Rulesets block force-pushes/deletion of `main` and moving/deleting `v*` tags (`.github/rulesets/`); `test` + `vulnerability-scan` are required checks on `main` (admin bypass, recorded); release immutability freezes released tags; `release.yml` cross-checks the tag version against `pom.xml`; attestation binds the published archive to its build | No required review on `main`, and the maintainer's admin bypass skips the required checks on direct pushes (each bypass is recorded); a bad commit is caught by tests/scans, not review |
| Tampered SBOM / SHA256SUMS after build | The archive, both CycloneDX SBOM serializations, and `SHA256SUMS` are subjects of one build-provenance attestation, each verified before publish; the publish job additionally checks the staged assets against the attested `SHA256SUMS` (`sha256sum -c`); repository release immutability (enabled) freezes the published asset set | Immutability covers only releases published after it was enabled (2026-07-22); earlier releases remain mutable and rest on their attestations alone |
| Shipped `THIRD-PARTY-NOTICES.md` misstates an upstream licence after a dependency bump, or a bump drags in a licence outside the approved set | cli's `license-maven-plugin` execution resolves every runtime dependency's effective-pom licence, normalizes it, and gates it against an approved allowlist; `DistTool` refuses to write notices when its hand-curated attribution map disagrees with that resolved report or the report is stale (`resolved-licence-report`, dist lanes) | Effective-pom licences are upstream-declared metadata, not scanned artifact contents; a pom that misstates its own jar's licence passes (mitigate with an `about.html`/`META-INF` spot-check when adopting a new dependency) |
| Malicious schema substitution | Java `HttpClient` requires HTTPS before and after redirects, bounds time/bytes, rejects invalid proxy configuration, and verifies a pinned SHA-256 before use (`SchemaCacheModule`) | `DEDIREN_XMI_SCHEMA_PATH` / `DEDIREN_OEF_SCHEMA_DIR` offline overrides bypass the SHA-256 check by design |
| Abuse a configured MCP PNG converter | `--resvg-command` resolves one bare-name or absolute executable at startup; no shell/arguments, cleared environment, stdin/stdout-only conversion, 15-second process bound, 4096-pixel dimensions, 64 MiB aggregate output, and PNG signature/IHDR validation; all failures fall back to JSON only | The operator explicitly selects and trusts the executable, which runs with the spawning user's authority; a malicious executable can still act with that user's OS permissions |
| Malicious envelope input | Jackson 3 parsing plus fuzz-regression targets pinning the only-`JacksonException`/`XmiValidationException` invariant. Two different XML factories guard two different trust contexts and neither is used for the other's: the hardened DOM factory (`SchemaValidation.secureXmiDocumentBuilderFactory()`) disallows DOCTYPE outright and is pointed only at XML the product generated; the hardened StAX factory (`SecureXml.inputFactory()`) is what reads attacker-supplied `.drawio` input | Fuzz targets run in deterministic regression mode over a fixed seed corpus in CI, not continuous coverage-guided fuzzing. The StAX configuration deliberately does *not* reject a `DOCTYPE` declaration — it renders it inert and refuses the entity *reference* — so a hostile file carrying a bare, unreferenced declaration parses cleanly (recorded decision, draw.io import boundary) |
| Read a local file or reach the network from a hostile `.drawio` | Entity resolution is closed twice over: `SUPPORT_DTD = false` makes a `DOCTYPE` inert and fails the parse the moment an entity it would have declared is referenced, and a throwing `XMLResolver` refuses the external DTD subset and external parameter entities if DTD support is ever re-enabled. Both the outer file and each decompressed `<diagram>` payload get their own hardened factory, with a mutation proof that a default factory on the inner parse would be observable and an exfiltration test asserting a real on-disk secret reaches neither the diagnostic message nor its path. Every non-entity route out of the file is refused by content: `link`/`linkTarget`, `shape=image`, any `image` or `imageBackground` style key, embedded `shape=stencil(...)`, and label HTML beyond the `<br>` family | `FEATURE_SECURE_PROCESSING` contributes nothing on the shipped runtime (`XMLInputFactory` does not support it and throws), so the guarded set-attempt is a no-op kept only for a third-party StAX provider arriving via `ServiceLoader`; and the `XMLResolver` never fires in the shipped configuration, so its correctness rests on mutation evidence rather than on a production path. The refusal list is an enumeration of known outward constructs — a future draw.io style key that loads a resource would need a matching entry |
| Exhaust the host via pathological model input (CPU/heap) | Input ceilings (core `SourceLimits`, applied via `BoundedReads` and `SourceValidator` on both CLI file/stdin and MCP lanes): 64 MiB per model-supplied input file, 1000 fragments, 100000 merged elements. Mermaid and DOT additionally cap statements, produced elements/groups, nesting, and token bytes inside their native parsers; Mermaid also caps label bytes, and comma-expanded DOT nodes count individually against the produced-element ceiling. draw.io caps the same input/element/nesting/token quantities and adds two the text importers have no analogue for: pages at 256, and a decompression budget equal to the 64 MiB input ceiling that is a running total across every page, enforced *during* the streamed inflate so a decompression bomb aborts with at most one 64 KiB chunk beyond the ceiling held. Its cells (200000, whole-document) and per-attribute bytes abort the streaming parse in place; nesting is resolved at page end instead, bounded by the cell ceiling rather than mid-parse. Jackson 3's default nesting-depth cap (500) bounds nested JSON; the MCP transport bounds a single inbound frame at 16 MiB (`FrameSplitter`) | Compute on a legal-size model is unbounded: ELK layout has no timeout or cancellation, SVG output is materialized in memory, and MCP tool calls have no per-call timeout. Accepted: the process runs with the invoking user's own authority and the MCP client owns the server's lifetime, so a wedged or OOM-killed process is recoverable by the user who caused it. draw.io import carries no compression-ratio cap, deliberately: raw DEFLATE tops out near 1032:1, so the absolute byte budget already bounds the worst case and a ratio cap would add a tunable and a diagnostic code without bounding anything new — do not "fix" it by adding one |
| Smuggle a source model past the model contract by way of foreign-text import | An import engine hands `core` a typed `SourceDocument`, not bytes, so an imported model would otherwise reach consumers without the gates every hand-authored model meets. `CoreCommands.importCommand` therefore re-enters the emitted document into `SourceValidator.gateImportedDocument`: schema version, `schemas/model.schema.json`, and the same `SourceLimits` ceilings, for every importer rather than a named one. A rejection is a `DEDIREN_SCHEMA_*` / `DEDIREN_SOURCE_*_LIMIT_EXCEEDED` error envelope at the plugin-error exit (3) — a rejection here means an engine emitted an out-of-contract document, not that the caller mistyped input, which the importer's own ceilings already reject at exit 2 — carrying the importer's own diagnostics ahead of core's; the engine's atomic parse failures are still republished verbatim, path and exit code untouched | The re-gate is the schema and the ceilings only. Model-coherence claims (duplicate ids, dangling endpoints) stay the importer's, so a defective importer can still emit a schema-legal but incoherent model — caught later by `validate`/`build`, not at import. It costs one serialize/reparse per import, and bounds contract conformance and model size, not the import's own CPU: the parser's internal caps are what bound that (resource-exhaustion row above) |
| Inject markup into a rendered SVG via model labels/ids | Labels: `SvgWriter` (StAX) structurally escapes every attribute value and text node at emission, with no verbatim-injection path; `LabelInjectionTest` proves an end-to-end breakout payload stays escaped and round-trips. Ids used as reference targets (via `url(#…)` in marker references and gradient fills): `SvgIds` mints every id and every reference to it from one per-document instance, constraining the alphabet and suffixing collisions, so a reference can never disagree with the id it points at. `SvgAudit` rejects ill-formed output | Escaping guards labels against markup breakout. For ids used as SVG identifiers and reference targets, the control is the id-minting alphabet, not escaping: a duplicate id makes a `url(#…)` reference ambiguous (UA binds to first match), and an id with `)` or space truncates the CSS url token so the reference silently misses and the marker is dropped |
| Dependency compromise | Known-vulnerability coverage comes from the blocking Grype/SBOM gate on every pull request and tagged release (`ci.yml`, `release.yml`), plus weekly grouped Dependabot updates and event-driven Dependabot alerts (`.github/dependabot.yml`) | Exact pins and hashes establish the selected dependency identity and integrity, but do not prove that an upstream release is benign. A newly malicious or compromised dependency release remains a human trust decision and residual risk. Direct pushes to `main` are not CI-scanned (lean-CI decision), so newly published advisories surface via Dependabot alerts or the next PR/release gate rather than a push-time scan; the scheduled OWASP Dependency-Check cross-check was retired with the same decision (`-Psecurity-sca` remains an on-demand local second opinion) |
| JVM-argument injection via `DEDIREN_LOG_LEVEL` | The launcher interpolates this env var into `JAVA_OPTS`, so it accepts only the six literals `trace\|debug\|info\|warn\|error\|off`; anything else is dropped with a note on stderr. A `-Pdist-smoke` probe asserts a smuggled `-XshowSettings:properties` neither reaches the JVM nor switches logging on | The guard is a shell `case` in the generated launcher; a caller who can already set arbitrary `JAVA_OPTS` needs no such trick, so this only closes the narrower "can set DEDIREN_* but not JAVA_OPTS" path |
| A model reads or writes outside the server root via an MCP tool (`dediren mcp`) | Source, package, policy, and source-mode output paths resolve against `--root`; package-declared outputs resolve relative to their package directory. Each is `toRealPath()`-anchored before containment, so an outward symlink is rejected (`WorkspacePaths` / `ConfinedPaths`) with `DEDIREN_MCP_PATH_OUTSIDE_ROOT`. Source `fragments[]` are confined to `--root`; package inputs remain package-relative and root-confined while declared outputs are package-relative and package-confined. `dediren_verify` and `dediren_status` remain server-root-relative. `--read-only` withholds build | Resolve-then-open is not atomic (TOCTOU): a local attacker able to mutate symlinks inside the root during the window can defeat the check. Accepted — the server runs with the spawning user's authority, and the controls contain model-selected paths rather than a hostile local user |
| A stray write corrupts the MCP JSON-RPC frame stream | In stdio MCP stdout *is* the protocol channel, so `StdoutIntegrity.claimStdout()` hands the transport the real stdout file descriptor and repoints `System.out` at stderr — a stray print anywhere in `core`, an engine, or a dependency degrades to log noise instead of corrupting a frame. The `-Pdist-smoke` probe asserts every stdout line of a real `bin/dediren mcp` run is a JSON-RPC frame | A frame silently lost after the session closes is the failure mode this boundary is most exposed to; requests are id-correlated and held open until answered, and an expired backstop names the unanswered ids on stderr rather than exiting quietly |
| Shipped classes silently diverge from vetted dependencies (shrinker defect or compromised ProGuard) | ProGuard version pinned in root `dependencyManagement` and resolved from Maven Central like every dependency (aggregate SBOM + Grype gate scan it); the pass does no optimization and no renaming (the obfuscation phase runs only as an attribute filter, names pinned) with keep rules reviewed in-repo and warning suppression scoped to named optional platforms (an unexpected unresolved reference fails the dist build); `-Pdist-smoke` drives layout, render, both exports, and MCP stdio against the packaged shrunk archive on every pull request (`ci.yml`) and release build | Reachability shrinking can drop reflection-only code paths the smoke never exercises; the SBOM lists upstream components while shipped bytes are shrunk subsets, so per-jar upstream hash comparison no longer applies — the bundle-level provenance attestation remains the integrity signal |
| Sensitive data disclosed in debug logs | Logging is `off` by default and must be switched on per run; first-party code cannot log above `debug` (ArchUnit-enforced), and logs go to stderr, never the stdout envelope | With `DEDIREN_LOG_LEVEL=debug` a log line carries filesystem paths (schema cache, schema files), schema URLs, engine ids, and node/edge **counts**. No current call site logs model element ids, labels, or document content — keep it that way: a label is author-supplied text, and logging one would turn a debug switch into a content-disclosure channel. Debug output should still not be pasted into a public issue unreviewed, since the paths alone can leak a local directory layout |

## Incident Response Runbook

1. **Intake**: report through GitHub private vulnerability reporting — same
   URL as `SECURITY.md`.
2. **Triage** against the SLAs in `SECURITY.md`'s "Response Expectations"
   section; do not re-derive them here.
3. **Fix on `main`** — this repo takes direct commits to `main` per
   `git-workflow-policy` in `CLAUDE.md`; branches are optional.
4. **Release** through the attested release workflow
   (`.github/workflows/release.yml`): SBOM + Grype gate, build, attest
   provenance, verify attestation, publish.
5. **Disclose** via the GitHub release notes and, for a confirmed
   vulnerability, a GitHub security advisory.
6. **Post-fix**: add a regression test for the failure class (fuzz seed
   corpus entry, unit test, or fixture) and update this threat model in the
   same change if a described surface changed.

## Maintenance Rule

This page changes in the same commit/PR as any change to the trust
boundaries it describes: the single-JVM engine runtime, schema-cache
fetching, envelope validation, XML parser hardening, or release workflows.
