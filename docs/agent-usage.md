# Dediren Agent Usage

This guide is for agents that author Dediren JSON and run a packaged Dediren
bundle. Use schemas for exact validation and fixtures for examples, but use
this file to decide which JSON to write, which JSON is generated, and how to
repair failures.

## Fast Path

1. Author `model.json` with the `Minimal Source JSON` shape below.
2. Add `plugins.generic-graph.views[]` with the nodes and relationships for
   each view.
3. Reuse `fixtures/render-policy/default-svg.json` unless custom SVG style is
   required.
4. Run `build --render-policy <policy> --out <dir>` (add `--oef-policy`
   and/or `--xmi-policy` for export lanes). It chains `project` → `layout` →
   `validate-layout` → `render`/`export` for every view and writes each
   view's artifacts under `--out/<view-id>/` — see `## Build`. Fall back to
   the decomposed form — `validate`, `project --target layout-request`,
   `layout`, `validate-layout`, then `render` or `export` — to run a single
   stage, inspect an intermediate result, or reuse a cached stage output.
5. Inspect stdout JSON `.status` and `.diagnostics[]`; do not parse stderr.

If the starting point is a Mermaid flowchart, run
`dediren import --plugin mermaid --input diagram.mmd` (or pipe the diagram on
stdin), save the envelope's `.data` as the source model, then continue at step
3. Import is deliberately one-way; Dediren does not export Mermaid.

If the starting point is a Graphviz DOT file, run
`dediren import --plugin dot --input diagram.dot` (or pipe it on stdin)
instead, then continue at step 3 the same way — see `## DOT Graph Import`.
Import is one-way for DOT too; Dediren does not export it.

## Mermaid Flowchart Import

Dediren implements a native Java subset based on the
[Mermaid 11.16.1 flowchart grammar](https://github.com/mermaid-js/mermaid/blob/mermaid@11.16.1/packages/mermaid/src/diagrams/flowchart/parser/flow.jison).
It accepts one `flowchart` or `graph` diagram with root
directions `TB`/`TD`, `BT`, `LR`, or `RL`; common node shapes and Unicode
labels; solid directed `-->` and undirected `---` edges, labels, and chains;
`%%` comments; semicolon-separated statements; and nested `subgraph` blocks.
A node or edge chain may continue across physical lines, and a balanced quoted
label may span lines; diagnostics still identify the original physical line
and column. Inside node and edge labels only, case-insensitive `<br>`, `<br/>`,
and `<br />` become newline characters. Output is a `model.schema.v1`
generic-graph model with `generic.node`, `generic.link` for directed edges,
`generic.edge` for undirected edges, view `main`, the root direction, and
layout-only groups for subgraphs.

Presentation-only shapes and `style`, `class`, `classDef`, `linkStyle`, and
subgraph `direction` hints are discarded and summarized by one
`DEDIREN_MERMAID_HINT_IGNORED` warning. Interactive `click`/`href`, external
resources, every other HTML tag or tag spelling, image syntax, unsupported
diagram families, ambiguous subgraphs, and non-solid or bidirectional edges
fail atomically with exit 2; an error envelope contains no partial model. Bare
structural or directive keywords are not node IDs; labels may still contain
those words. Undirected edges also contribute one aggregated
`DEDIREN_MERMAID_HINT_IGNORED` warning because the default shipped render
policy does not suppress their arrowheads.

IDs already legal under Dediren's
`^[A-Za-z0-9][A-Za-z0-9._-]*$` contract are preserved and reserved first.
Other accepted Mermaid IDs are normalized deterministically: runs of ASCII
punctuation become `-`, each non-ASCII code point becomes `-u` plus lowercase
hex (at least four digits), leading punctuation is prefixed with `node-`, and
collisions receive `-2`, `-3`, and so on in source order. A changed node keeps
its original under `properties.mermaid.original_id`.

Limits are 64 MiB UTF-8 input, 200000 statements, 100000 produced nodes plus
relationships plus groups, 256 nested groups, and 64 KiB UTF-8 per token or
label. At the ceiling is accepted; the first value above it is rejected with a
`DEDIREN_MERMAID_*_LIMIT_EXCEEDED` (or `INPUT_TOO_LARGE`) diagnostic at `$`.
Syntax and compatibility errors report a 1-based `line N, column N` path.

## DOT Graph Import

Dediren implements a native Java subset of the Graphviz DOT language (run with
`dediren import --plugin dot`). It accepts `graph` or `digraph`, the `strict`
keyword, node and edge statements, edge chains, quoted identifiers, `subgraph`
blocks (including `cluster_`-prefixed ones), `graph`/`node`/`edge`
default-attribute statements scoped to the subgraph that declares them,
comma-separated node declarations such as `a, b [shape=diamond]`, and `/* */`,
`//`, and `#` comments. Active node defaults and explicit declaration
attributes apply to every node in the list. Output is a `model.schema.v1`
generic-graph model.

Nodes become `generic.node`. Edges from a `digraph` become `generic.link`;
edges from an undirected `graph` become `generic.edge`. An element's `label`
attribute becomes its Dediren label, falling back to its original DOT id when
no `label` is set. `subgraph`/`cluster_` blocks become view groups with role
`layout-only`. A graph-level `rankdir` of `TB`/`LR`/`RL`/`BT` becomes
`layout_preferences.direction` `down`/`right`/`left`/`up`. Every other
attribute Dediren does not otherwise consume is kept under
`properties.dot.attributes` rather than dropped.

HTML-like labels, ports and compass points (`node:port`), subgraph-shorthand
edges (`{a b} -> {c d}`), and the anonymous brace-only subgraph shorthand are
not part of the supported subset. They are never silently dropped: each fails
the import atomically with `DEDIREN_DOT_UNSUPPORTED_CONSTRUCT` so you can
rewrite the input without them; no partial model is produced.

The unquoted words `strict`, `graph`, `digraph`, `subgraph`, `node`, and `edge`
are reserved wherever DOT requires an identifier, including graph/subgraph
names, node and endpoint IDs, and attribute keys or values. Quote one of those
words when it is intended as an ID; quoted forms are ordinary identifiers.

IDs already legal under Dediren's `^[A-Za-z0-9][A-Za-z0-9._-]*$` contract are
preserved. Other ids are normalized to that charset, and a collision after
normalization gets a `-2`, `-3`, ... suffix in source order. A node or edge
whose id changed keeps the original under `properties.dot.original_id`; ids
that did not change do not get that property at all.

Two limitations are worth knowing about before you rely on the output:

- No shipped render policy styles `generic.edge`, so an imported undirected
  graph currently renders with arrowheads on every edge until you add a
  `marker_end: none` entry under `edge_type_overrides` in your render policy.
- Nested clusters are flattened: each cluster becomes its own top-level view
  group, and the nesting relationship between an outer and inner cluster is
  not preserved in the imported model.

Limits match the Mermaid importer's ceilings: 64 MiB UTF-8 input, 200000
statements, 100000 produced nodes plus relationships plus groups, 256 nested
subgraphs, and 64 KiB UTF-8 per token. At the ceiling is accepted; the first
value above it is rejected with a `DEDIREN_DOT_*_LIMIT_EXCEEDED` (or
`INPUT_TOO_LARGE`) diagnostic at `$`. Syntax errors report a 1-based
`line N, column N` path.

## MCP Server

`dediren mcp` runs an MCP stdio server so an agent can drive Dediren as tools
instead of shelling out. Register it once:

    claude mcp add dediren -- /path/to/bundle/bin/dediren mcp --root /path/to/your/project

Eight tools in writable mode:

- `dediren_guide` — this document, one section at a time. Pass `topic`, or omit
  it to list the topics. Start with `topic: "source-json"`.
- `dediren_import` — pass exactly one of `source` (a confined file path) or
  inline `content`, with `plugin: "mermaid"` or `"dot"`. Select
  `output: "data"` (default), `"svg"`, or `"image"`. `"svg"` strictly forces
  an `image/svg+xml` attachment; `"image"` negotiates against
  `accepted_image_types`. When either mode selects an attachment, import falls
  back to the bundled `fixtures/render-policy/default-svg.json` unless
  `render_policy` is given.
  Prompt-first examples are
  `content: "flowchart LR\n  a --> b"` and `content: "digraph { a -> b }"`.
  Returns the envelope without writing files and remains available under
  `--read-only`; inline input has the same 64 MiB and parser ceilings above.
- `dediren_validate` — `source` (path to a source model **or a policy
  document**: the schema-version field selects the family, so a render/export
  policy or kept layout-request gets its version gate + JSON Schema check
  here instead of only at build time); optional `profile` to also run
  semantic profile validation (source models only). Returns the validation
  envelope.
- `dediren_diff` — `old` and `new` (two source-model paths). Returns an envelope
  of change records — nodes, relationships, and views added, removed, or
  changed. Both sides must share the same schema id.
- `dediren_query` — `source` and `kind` (`dependents` | `orphans` |
  `view-coverage`); `id` is required when `kind` is `dependents`. A fixed query
  vocabulary over one model, not a query language.
- `dediren_verify` — `source` and `artifacts` (a directory of built artifacts)
  relative to the server `--root`.
  Checks each artifact's provenance stamp against the model's recomputed hash:
  `ok` means all current; a stale artifact is an error, an unstamped one a
  warning.
- `dediren_status` — optional `dir` relative to `--root`; omit it to index
  the server root itself.
- `dediren_build` — `source`, `out`, and at least one policy (`render_policy`,
  `oef_policy`, `xmi_policy`); optional `views` (subset of view ids) and `emit`
  (extra stage envelopes to also write, for debugging). Returns the
  build-result envelope, which names every artifact written. To build a whole
  **package** instead, pass `package` (a `package.json` path) — mutually
  exclusive with `source`/`out`/the policies — plus optional `no_export`; the
  package uses its declared render policies and output paths.
  Both source and package builds accept `output: "data"` (default),
  `output: "svg"`, or `output: "image"`; a source build requires
  `render_policy` when SVG is forced or an image type is negotiated, while a
  package build uses its declared policies.
  `output: "image"` uses `accepted_image_types` to negotiate an optional
  attachment. The result is a `package-build-result` naming every artifact at
  its declared path
  (see `## Build`). Source, package, policy, and source-mode `out` paths are
  relative to `--root`; package-declared outputs are relative to the package
  directory. This removes the former workspace-handle requirement and
  restores earlier MCP clients: do not pass `workspace_id`. Existing
  `.dediren/mcp/workspaces/` files are neither migrated nor deleted
  automatically; recover wanted artifacts and remove the residue manually.

The server also serves read-only **resources** — the bundle's own bytes, so a
fetch returns ground truth rather than prose about it:
`dediren://schema/<file>` (every public JSON schema),
`dediren://fixture/<relative-path>` (every bundled fixture),
`dediren://guide/<topic>` (the same sections `dediren_guide` serves), and
`dediren://diagnostics/catalog` (every `DEDIREN_*` code paired with its
explicit `## Repair Rules` text, or `null` for codes that are self-repairing
via their message). Resources are product-owned content, and the full set is
served identically under `--read-only`.

Every tool path must resolve inside `--root`, and so must every `fragments[]`
path inside a source you pass. Point `--root` at your project directory — an
absolute path is safest, because a bare `.` resolves against wherever the MCP
client spawns the server, which is not guaranteed to be your project. Claude
Code sets `CLAUDE_PROJECT_DIR` in the server's environment (not at
config-expansion time, so `${CLAUDE_PROJECT_DIR:-.}` in a hand-written
`.mcp.json` falls back to `.`); to make `--root` track the project
automatically, wrap the launch in a script that reads `$CLAUDE_PROJECT_DIR` at
runtime, as the plugin distribution does. A path that escapes `--root` returns a
`DEDIREN_MCP_PATH_OUTSIDE_ROOT` error envelope. Launch with `--read-only` to
withhold the artifact-writing `dediren_build`; the seven read-only tools
(`dediren_import`, `dediren_guide`, `dediren_validate`, `dediren_diff`, `dediren_query`,
`dediren_verify`, `dediren_status`) all remain.

Tool results carry the same envelope JSON the CLI prints on stdout, so the
handoff rules in `## Command Handoff` apply unchanged.

Image responses are JSON-first: the unchanged command envelope is always the
first `TextContent`, followed by any base64 image attachments in selected-view
order. The selectors are:

- `output: "data"` (or omitted) returns only the envelope.
- `output: "svg"` is the compatibility force-SVG mode. It attaches
  `image/svg+xml` and treats an unreadable, malformed, or over-limit generated
  SVG as a tool error.
- `output: "image"` is negotiation. Supply `accepted_image_types` as a unique
  array containing `image/svg+xml`, `image/png`, or both. SVG has fixed
  priority regardless of array order. If the field is omitted or empty, no
  type matches and the result is JSON only. The field is invalid with another
  output mode, as are duplicate or unknown MIME types.

PNG is response adaptation owned by the MCP layer, not a new render artifact:
the render policy and core build still produce SVG. PNG is attempted only when
`image/png` is accepted and SVG is not. Start the server with
`--resvg-command resvg` (the default bare `PATH` lookup) or an absolute
executable path. Command arguments and relative paths containing separators are
not accepted; resolution happens once at server startup. If `resvg` is absent,
times out, exits unsuccessfully, emits an invalid/oversized PNG, or otherwise
cannot convert a valid SVG, `output: "image"` falls back to the unchanged JSON
envelope rather than failing the successful import/build. Partial builds may
still attach successful selected-view images while retaining `isError`.

The adapter invokes the resolved executable directly without a shell, with no
inherited environment values (`PATH` and `HOME` are set empty), writes SVG on
stdin, and captures PNG on stdout. Each conversion has a 15-second timeout;
output dimensions are capped at 4096 by 4096, the PNG signature/IHDR/dimensions
are checked, stderr capture is capped at 64 KiB, and decoded attachments share
a cumulative 64 MiB limit. Actual inline display depends on MCP client support.

`resvg` is optional and not bundled with Dediren. Its upstream licence is
MIT OR Apache-2.0; because operators supply the executable separately, it is
not a Dediren runtime dependency and has no Dediren
`THIRD-PARTY-NOTICES.md` entry.

## Artifact Map

| Artifact | Agent authors it? | Schema | Example |
| --- | --- | --- | --- |
| Source model | Yes | `schemas/model.schema.json` | `fixtures/source/valid-basic.json` |
| Render policy | Usually reuse | `schemas/render-policy.schema.json` | `fixtures/render-policy/default-svg.json` |
| OEF export policy | Usually reuse | `schemas/oef-export-policy.schema.json` | `fixtures/export-policy/default-oef.json` |
| UML/XMI export policy | Usually reuse | `schemas/uml-xmi-export-policy.schema.json` | `fixtures/export-policy/default-uml-xmi.json` |
| Layout request | Usually generated | `schemas/layout-request.schema.json` | `fixtures/layout-request/basic.json` |
| Render metadata | Usually generated | `schemas/render-metadata.schema.json` | `fixtures/render-metadata/archimate-basic.json` |
| Layout result | No | `schemas/layout-result.schema.json` | `fixtures/layout-result/basic.json` |
| Render/export result | No | `schemas/render-result.schema.json`, `schemas/export-result.schema.json` | command stdout |
| UML/XMI assurance | No | `schemas/uml-xmi-assurance.schema.json` | `.data.assurance` in UML/XMI export stdout |
| Build result | No | `schemas/build-result.schema.json` | command stdout (`build`) |

## Minimal Source JSON

```json
{
  "model_schema_version": "model.schema.v1",
  "required_plugins": [
    { "id": "generic-graph", "version": "2026.08.4" }
  ],
  "nodes": [
    { "id": "client", "type": "generic.actor", "label": "Client", "properties": {} },
    { "id": "api", "type": "generic.component", "label": "API", "properties": {} }
  ],
  "relationships": [
    {
      "id": "client-calls-api",
      "type": "generic.calls",
      "source": "client",
      "target": "api",
      "label": "calls",
      "properties": {}
    }
  ],
  "plugins": {
    "generic-graph": {
      "views": [
        {
          "id": "main",
          "label": "Main",
          "nodes": ["client", "api"],
          "relationships": ["client-calls-api"],
          "groups": []
        }
      ]
    }
  }
}
```

Do not put `x`, `y`, `width`, `height`, colors, fonts, or SVG shape choices in
source JSON. Source JSON is semantic. Layout results contain generated
geometry. Render policy contains presentation.

`required_plugins` is informational only: it names the bundled engines the
model expects for human readers, but the in-memory registry never enforces the
entries — commands run whether or not a matching entry is present.

Every emitted SVG names itself for assistive technology: the root `<svg>` has
`role="img"` with a `<title>` (and a `<desc>` when supplied). Set the text with
an optional `accessibility` block in the render policy, for example
`"accessibility": { "title": "Order Processing", "description": "Application cooperation view" }`;
without it the `<title>` falls back to the layout `view_id`, so shipped
diagrams should use a policy copy with a real title.

That text is authored prose, so tag its language: `accessibility.lang` (a BCP 47
tag such as `fi` or `ar-EG`) becomes `xml:lang` on the root, and
`accessibility.dir` (`ltr` or `rtl`) becomes `direction`, the base writing
direction every text element below inherits. Both are optional and are omitted
entirely when unset — nothing is defaulted, so an untagged policy renders exactly
as before. Set `lang` whenever the diagram is not in the reader's assumed
language (assistive technology otherwise guesses a pronunciation), and set `dir`
to `rtl` for right-to-left prose, which is laid out wrongly without it.

## Fragments

A source model may split across files: `fragments` is an array of relative
paths, resolved against the main source file's directory and merged into the
model before validation. Paths must stay relative; fragment files carry model
content only and must not declare `fragments` of their own. Over MCP, every
fragment path must also resolve inside `--root`, like any other tool path (see
`## MCP Server`).

Repair codes:

- `DEDIREN_FRAGMENT_BASE_DIR_REQUIRED`: the source arrived on stdin, so
  relative fragment paths have no base directory — pass a source file path
  instead.
- `DEDIREN_FRAGMENT_PATH_UNSUPPORTED`: the path is absolute — make it
  relative. A relative path that resolves outside `--root` over MCP is the
  separate `DEDIREN_MCP_PATH_OUTSIDE_ROOT` error (see `## MCP Server`), not
  this code.
- `DEDIREN_FRAGMENT_READ_FAILED`: no readable file at the resolved path.
- `DEDIREN_FRAGMENT_NESTED_UNSUPPORTED`: a fragment declared `fragments`;
  flatten the list into the main source.
- `DEDIREN_FRAGMENT_CONFLICT`: merging fragments hit a conflict — the same
  `required_plugins` id declared with two different versions, or the same
  `plugins` extension-data path merged to two different values. Duplicate
  node or relationship ids across merged fragments are `DEDIREN_DUPLICATE_ID`
  instead; duplicate view ids are `DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID`
  and duplicate group ids (scoped per view) are
  `DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID` (see `## Repair Rules`).

## Semantic Profiles

For ArchiMate® SVG notation or OEF export, set the generic graph semantic
profile and use ArchiMate type names:

```json
{
  "required_plugins": [
    { "id": "generic-graph", "version": "2026.08.4" },
    { "id": "archimate-oef", "version": "2026.08.4" }
  ],
  "plugins": {
    "generic-graph": {
      "semantic_profile": "archimate",
      "views": []
    }
  }
}
```

An `archimate-oef` export renders the single laid-out view it is handed and
preserves node/relationship `properties` via OEF `<propertyDefinitions>` and
per-element `<property>` values, so evidence-classification markers survive the
export. When the source declares more views than the exported one, the omission
is declared (rather than dropped silently) with the `info` diagnostic
`DEDIREN_OEF_VIEWS_OMITTED`, which names the omitted view ids and counts; the
envelope `status` stays `ok` for omissions alone (an unedited default policy
adds the `DEDIREN_EXPORT_IDENTITY_PLACEHOLDER` warning, which lifts it to
`warning` — see `## Export`). For whole-model interchange, `dediren build`
with `--oef-policy` additionally writes `model.oef.xml` at the `--out` root:
one document carrying every view's diagram the build supplied, listed under
`model_artifacts`; when the build covers only a subset of the declared views
(`--views`, or a view that failed to build), the aggregate declares the
missing diagrams with the same `DEDIREN_OEF_VIEWS_OMITTED` info diagnostic.
Each view in it carries its own identity: an explicit override from the policy's
optional `views` map (`"views": {"<view-id>": {"view_identifier"?,
"view_name"?, "viewpoint"?}}`), else the source-derived default
(`id-view-<view-id>`, the view's own label, the policy's top-level
`viewpoint`). The single-view lane keeps the legacy top-level view identity
fields unchanged. Because the document always carries a
`<views>`/`<diagrams>` element it declares and validates against
`archimate3_Diagram.xsd`, not the model-only `archimate3_Model.xsd`; point
`DEDIREN_OEF_SCHEMA_DIR` at a directory holding all three ArchiMate 3.1 OEF
XSDs plus, for the real Open Group set, the W3C `xml.xsd` they import.

For UML® SVG notation or XMI export, use `semantic_profile: "uml"` and the
`uml-xmi` plugin. Supported UML view kinds are `uml-class`, `uml-data`,
`uml-activity`, `uml-sequence`, `uml-state-machine`, `uml-use-case`,
`uml-component`, and `uml-deployment`.

On a successful UML/XMI export, inspect `.data.assurance` instead of inferring
support from the artifact filename or diagnostic prose. Its exhaustive
`kind_taxonomy` distinguishes standard UML diagram kinds from the local
`uml-data` classifier view, `artifact_scope` distinguishes a per-view artifact
from the model aggregate, and `coverage` reports represented, omitted, and
in-view-unrepresented source counts by type. Treat
`validation_evidence.level: "xmi-envelope-only"` literally: empty UML
metamodel/importer evidence means no such stronger validation was performed.

For whole-model interchange, `dediren build` with `--xmi-policy` additionally
writes `model.uml.xml` at the `--out` root: one `<uml:Model>` plus one OMG UMLDI
diagram (`umldi:UMLDiagram` with `dc:Bounds` shapes and `di:waypoint` edges) per
classifier-diagram view (`uml-class`, `uml-data`), listed under the build
result's `model_artifacts`. Each diagram's identity is an explicit override from
the policy's optional `views` map (`"views": {"<view-id>":
{"diagram_identifier"?, "diagram_name"?}}`), else the source-derived default
(`id-diagram-<view-id>` and the view's label). Other UML families still get their
per-view `xmi.xml` (model content only); diagram interchange for them is a later
slice.

The UMLDI dialect is **provisional in one specific sense: no UML tool has been
observed rendering it.** Its spelling is not in doubt. UML 2.5.1 Annex B is
normative and defines the UMLDI metamodel, which the emitted diagram element and
its `isFrame` attribute follow; the `dc:`/`di:` geometry follows the OMG's
published DD serialization schemas, whose `20100524` namespaces are what every
deployed DD-based dialect uses. (DD 1.1 exists and stamps its metamodel files
`20131001`, but that stamp is not an XML namespace — emitting it would produce
documents no DD-aware tool can read.)

Like the UML namespace, there is no normative OMG DI XSD, so this content rides
the same tolerated no-declaration gap the `uml:` model content does. Treat a
UMLDI document as structurally correct and not yet render-verified.

## ArchiMate Handoff

The `archimate` profile accepts exactly these type names.

Elements: `Plateau`, `WorkPackage`, `Deliverable`, `ImplementationEvent`,
`Gap`, `AndJunction`, `OrJunction`, `Grouping`, `Location`, `Stakeholder`,
`Driver`, `Assessment`, `Goal`, `Outcome`, `Value`, `Meaning`, `Constraint`,
`Requirement`, `Principle`, `CourseOfAction`, `Resource`, `ValueStream`,
`Capability`, `BusinessInterface`, `BusinessCollaboration`, `BusinessActor`,
`BusinessRole`, `BusinessProcess`, `BusinessService`, `BusinessInteraction`,
`BusinessFunction`, `BusinessEvent`, `Product`, `BusinessObject`, `Contract`,
`Representation`, `ApplicationInterface`, `ApplicationCollaboration`,
`ApplicationComponent`, `ApplicationService`, `ApplicationInteraction`,
`ApplicationFunction`, `ApplicationProcess`, `ApplicationEvent`, `DataObject`,
`TechnologyInterface`, `TechnologyCollaboration`, `Node`, `SystemSoftware`,
`Device`, `Facility`, `Equipment`, `Path`, `TechnologyService`,
`TechnologyInteraction`, `TechnologyFunction`, `TechnologyProcess`,
`TechnologyEvent`, `Artifact`, `Material`, `CommunicationNetwork`,
`DistributionNetwork`.

Relationships: `Composition`, `Aggregation`, `Assignment`, `Realization`,
`Specialization`, `Serving`, `Access`, `Influence`, `Flow`, `Triggering`,
`Association`. `AndJunction`/`OrJunction` are relationship connector nodes.

Relationship endpoint pairs are validated against Dediren's metamodel-derived
legality rules, keyed on each element's ArchiMate category (active structure,
behavior, service, event, passive structure, motivation, composite) and the
relationship's semantics, so direction matters (for example `ApplicationComponent
--Realization--> ApplicationService` is valid but the reverse `ApplicationService
--Realization--> ApplicationComponent` is diagnosed, and `ApplicationFunction
--Access--> DataObject` is valid but `Access` to a non-passive target is
diagnosed). `Association` is always accepted (the unspecified relationship).
`Grouping` and `Location` are universal in the conditional sense §B.6 states:
they take part in a relationship whenever the other endpoint could itself take
part in it, so `Grouping --Access--> DataObject` is accepted while
`Grouping --Access--> BusinessProcess` is not — no element is a legal `Access`
target there.

The check is a sound under-approximation of ArchiMate Appendix B: it never
rejects a valid combination except a small, deliberately-rejected
§5-contradicted set — dynamic relationships (Triggering/Flow) touching
motivation or passive elements, and Assignment from passive, motivation, event,
or service sources.

**A green `validate` is not a conformance certificate.** About one endpoint
combination in eight that Appendix B forbids is still accepted — measured
against the specification's own relationship table, the model rejects 88.2% of
them and falsely rejects none. That is a design point rather than a bug backlog: the rules are expressed over the generic
metamodel's element categories rather than by reproducing Appendix B's tables,
and they do not compute the full derivation closure. Three specific gaps are
worth knowing when a model matters:

- Containment is decided on element *category*, not element type, so a business
  actor composing an application component passes. Tightening it is not simply a
  matter of stricter equality — a business process composed of business
  functions is legal and must stay so.
- §B.4's domain crossings *are* enforced (Motivation, Strategy, Core,
  Implementation & Migration), as are `Product`'s closed containment list
  (§8.5.1) and grouping/location/plateau containment (§B.6). What is not
  computed is Appendix B's derivation closure, which is where most of the
  remaining residue lives.

A model that must be legal in Archi or Enterprise Architect should be checked
there. `dediren validate` catches the bulk of endpoint errors early; it does not
replace the tool that owns the standard.

**Relationship attributes are not modelled.** `Access` carries no `accessType`
(read / write / read-write / access), `Influence` no sign or strength, and
`Association` no directedness — the exchange format defines enumerations for the
first two, and an export simply omits them. A read-only access and a write
access are the same edge in both the SVG and the OEF. Relationships also cannot
be relationship endpoints, so §5.2.4's association-to-a-relationship has no
expression.

**`viewpoint` is a required free string.** It is copied verbatim into the
exported view, and the exchange format types it as a union that accepts any
string, so any value exports cleanly. A name outside the format's own viewpoint
vocabulary draws a `warn` `DEDIREN_OEF_VIEWPOINT_UNKNOWN` with the nearest
known name when it looks like a typo. The specification's per-viewpoint content
restrictions (§13.4 — which element types each viewpoint admits) are not
enforced at all: declaring `"Layered"` does not constrain what the view may
contain.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile archimate \
  --input "$BUNDLE/fixtures/source/valid-pipeline-archimate.json"
```

Continue with the Bundle Smoke Workflow commands, using
`--policy "$BUNDLE/fixtures/render-policy/archimate-svg.json"` for ArchiMate
SVG notation, and the OEF export under `## Export`.

## UML Class Handoff

The `uml-class` and `uml-data` view kinds carry classifiers and their
relationships. Author `Package`, `Class`, `Interface`, `DataType`, and
`Enumeration` nodes; classifiers nest under the `Package` they declare via
`properties.uml.package`. Use `fixtures/source/valid-uml-basic.json` for the
class MVP and `valid-uml-complex.json` for a fuller one.

Class members are authored on the node, and are the surface most easily missed
because they live inside `properties.uml`:

```json
{
  "id": "order-service",
  "type": "Class",
  "label": "OrderService",
  "properties": {
    "uml": {
      "attributes": [
        { "name": "id", "type": "String", "visibility": "private",
          "multiplicity": "1" }
      ],
      "operations": [
        { "name": "placeOrder", "visibility": "public", "return_type": "Order",
          "parameters": [ { "name": "request", "type": "OrderRequest" } ] }
      ]
    }
  }
}
```

`visibility` takes exactly `public`, `private`, `protected` or `package`
(§7.4.4.3 `VisibilityKind`); any other value is rejected with
`DEDIREN_UML_ELEMENT_PROPERTY_UNSUPPORTED`. It is case-sensitive and not
trimmed — `"Private"` and `"protected "` are errors, not synonyms. SVG renders
it as `+`, `-`, `#` or `~`, and XMI emits it verbatim, so the two agree.

`multiplicity` accepts `*`, a non-negative integer, or a `lower..upper` range
whose upper bound may be `*`. Both bounds are integers here; the specification
allows any ValueSpecification, so an expression-valued bound cannot be authored.
`0..0` is legal and means the property is always empty.

Operation `parameters` carry `name` and `type` only. `ParameterDirectionKind`
has four values and dediren models the two that need no surface — every
parameter is emitted `in`, and the return type as `return`. **An `out` or
`inout` parameter cannot be expressed, and authoring one as an ordinary
parameter exports it as `in`**, silently changing the signature's meaning. Model
such an operation's outputs in the return type, or note the direction in the
name, until a direction surface exists.

This is also the only family that emits UMLDI diagram interchange — see the
UMLDI paragraph under `## Export`.

## UML Export Contract

A `uml-xmi` export represents the single laid-out view it is handed, not the
whole source model. It emits UML 2.5.1 abstract syntax for whatever view kind is
exported: class/data classifiers and their relationships (`Association`,
`Aggregation`, `Composition`, `Dependency`, `Realization`, `Generalization`)
with operation signatures; use-case actors, use cases, and their associations;
activities with partitions and edge guards; state machines with transition
triggers, guards, and effects; components with typed ports and their
realizations; and deployments with nested nodes, deployments, and
manifestations.

**Two known departures from the abstract syntax**, both recorded rather than
claimed away. A class-to-interface `Realization` emits `uml:Realization`, where
§10.5.6 defines an `InterfaceRealization` nested under the implementing
classifier and naming its `contract`; component realization is likewise emitted
as the generic metaclass. And every `Association` end is emitted non-navigable
in both directions, which is an assertion rather than an omission — there is no
source surface for navigability to carry the intent. Classifiers nest under the `Package` they declare via
`properties.uml.package`. When the source model contains elements or
relationships outside the exported view — or in-view content the UML/XMI mapping
could not represent — the export declares them (rather than dropping them
silently) with `info` diagnostics `DEDIREN_XMI_ELEMENTS_OMITTED` and
`DEDIREN_XMI_RELATIONSHIPS_OMITTED`, each listing the count and a per-type
breakdown (the message states whether the content was outside the view or an
in-view mapping gap); the envelope `status` stays `ok` for these alone (an
unedited default policy adds the `DEDIREN_EXPORT_IDENTITY_PLACEHOLDER`
warning, which lifts it to `warning` — see `## Export`). Read those diagnostics
from `.diagnostics[]` to know exactly what a given XMI does and does not cover,
and export the other views to represent their content.

Class content is canonical UML 2.5.1: every attribute `type` resolves to an
`xmi:id` in the document (an emitted classifier, or a self-contained
`uml:PrimitiveType`/`uml:DataType` synthesized for standard primitives and
domain types) rather than a dangling type-name string, and multiplicities are
owned `lowerValue` (`uml:LiteralInteger`) / `upperValue`
(`uml:LiteralUnlimitedNatural`, `*` for unbounded) value-specification children
rather than XML attributes. To schema-check the emitted UML content, point
`DEDIREN_XMI_SCHEMA_PATH` at a driver schema that imports the OMG `XMI.xsd` and
a UML 2.5.1 XSD (both sitting beside the driver — imports resolve local-only
from its directory); OMG does not publish an importable UML 2.5.1 XSD, so
supply or generate one, or import the document into a UML tool. Without a UML
schema only the XMI envelope is checked — the export's
`DEDIREN_EXPORT_SCHEMA_CONFORMANCE` diagnostic states which case applied.

## Command Handoff

Commands that consume generated artifacts accept either the raw artifact JSON
or the previous command envelope:

```bash
"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph \
  --view main --input "$BUNDLE/fixtures/source/valid-basic.json" \
  > layout-request.json

"$BUNDLE/bin/dediren" layout --plugin elk-layout \
  --input layout-request.json \
  > layout-result.json

"$BUNDLE/bin/dediren" render --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json

jq -r '.data.artifacts[] | select(.artifact_kind=="svg") | .content' render-result.json > diagram.svg
```

The `render` plugin and ordinary CLI emit only an `svg` artifact; they do not
produce PNG. To get a raster artifact, convert the emitted SVG with an external
tool — for example `rsvg-convert diagram.svg -o diagram.png`,
`resvg diagram.svg diagram.png`, ImageMagick
(`magick convert diagram.svg diagram.png`), or Inkscape
(`inkscape diagram.svg --export-type=png`). MCP `output: "image"` can instead
return an optional PNG attachment as described in `## MCP Server`; it does not
add a PNG artifact to the envelope or output directory.

### Layout constraints in a hand-written layout-request

`project` emits any needed `constraints` for you, so most agents never write them.
If you author a `layout-request` by hand, the vocabulary is:

```json
"constraints": [
  { "id": "band-1", "kind": "ordered-band:x", "subjects": ["lifeline-a", "lifeline-b@48"] }
]
```

- `kind` is `ordered-band:x`, `ordered-band:y`, or `stem-span`. An ordered
  band's subjects form an ordered band along that axis (this is how UML
  sequence lifelines and message rows are placed). A `stem-span` constraint
  carries exactly four subjects — `[node-id, band-member-id, from-member-id,
  to-member-id]` — anchoring a node (an execution specification or a
  destruction marker) to the named band member's stem, spanning the rows of
  the `from`/`to` members; an empty `from`/`to` id anchors the node one
  message step below the last member instead of spanning a range (the case
  for a destruction marker with no targeting message). `project` emits
  `stem-span` only for a sequence view that has execution or destruction
  nodes to place — a plain lifelines-and-messages view sees only the two
  `ordered-band` kinds. An unrecognised `kind` is rejected by the layout
  engine.
- Each ordered-band subject is a node id, optionally `@` plus a leading gap in
  layout units (`lifeline-b@48` leaves 48 units before that member).
- The `@` separator is unambiguous: the id charset
  (`[A-Za-z0-9][A-Za-z0-9._-]*`) cannot contain `@`. A subject whose `@` tail
  is not a number is rejected by the layout engine, not silently dropped.

## Build

`dediren build` runs the whole per-view pipeline — `project` (layout-request,
then render-metadata when `--render-policy` is set) → `layout` →
`validate-layout` → one or more of `render`/`archimate-oef`/`uml-xmi` — as one
process call, chaining the exact same stage paths the decomposed commands
above use, and writes each view's artifacts under `--out`:

```bash
"$BUNDLE/bin/dediren" build \
  --input "$BUNDLE/fixtures/source/valid-basic.json" \
  --out out \
  --render-policy "$BUNDLE/fixtures/render-policy/default-svg.json"
```

| Flag | Meaning |
| --- | --- |
| `--input <path>` | Source model JSON; default stdin. |
| `--out <dir>` | Output directory (required). Each view writes under `<out>/<view-id>/`. |
| `--views <id,id,...>` | Views to build, in the given order; default is every view in model order. |
| `--render-policy <path>` | Enable the SVG render lane; writes `<view-id>/diagram.svg`. |
| `--oef-policy <path>` | Enable the ArchiMate OEF export lane; writes `<view-id>/oef.xml`. |
| `--xmi-policy <path>` | Enable the UML/XMI export lane; writes `<view-id>/xmi.xml`. |
| `--emit <kinds>` | Comma-separated subset of `layout-request,layout-result,render-metadata` stage command envelopes to also persist under `<view-id>/`; see below. |

At least one of `--render-policy`/`--oef-policy`/`--xmi-policy` is required;
zero lanes is a rejected input (`DEDIREN_COMMAND_INPUT_INVALID`, exit `2`).
An artifact write failure (an unwritable or colliding `--out`) yields a
`DEDIREN_COMMAND_IO_FAILED` error envelope on stdout with exit `2`.

Build's own stdout **is** the build result document — unlike every other
command, it is not wrapped in the generic envelope's `.data`. Read it
directly:

```bash
jq -r '.status, (.views[] | .view_id, .status, (.artifacts[] | .artifact_kind + " " + .path))' build-result.json
```

- `.status` / `.views[].status` are `ok`, `warning`, or `error`, following the
  same rollup vocabulary the per-stage envelopes use: a view is `error` if any
  of its stages failed (it stops at that stage, so `.views[].artifacts[]` may
  be partial for it), `warning` if a stage warned, else `ok`; the build's own
  `.status` is the worst of its views'. One failing view never aborts the
  others, so read every `.views[].status` rather than stopping at the first.
- `.views[].artifacts[]` lists each written file as `{ "artifact_kind": ...,
  "path": "<view-id>/<file>" }`, relative to `--out`.
- A build-level failure (no lane selected, or the source itself fails
  `validate`) never runs any view: `.views` is empty and the failure's
  diagnostics sit on the top-level `.diagnostics[]` instead of nested under a
  view.
- A model that declares zero views (`plugins.generic-graph.views: []`, with no
  explicit `--views`) is not an error: `.status` is `ok` and `.views` is
  empty — there is simply nothing to build.

`--emit` persists **stage command envelopes**, not the build result's own
shape: each requested kind is the exact JSON a per-stage subcommand above
would print — `{ "envelope_schema_version", "status", "data", "diagnostics" }`
with the generated data nested under `.data` — written verbatim to
`<out>/<view-id>/<kind>.json` (for example `<out>/main/layout-result.json`).
Use it to debug a specific stage or hand an intermediate result to another
tool without re-running the decomposed flow.

The `archimate-oef` lane's OEF policy identity (`model_identifier`,
`view_identifier`, `model_name`, `view_name`) is per-build, not per-view
(Phase-1 limitation): building several views with `--oef-policy` writes one
`oef.xml` per view, but every one carries the *same* policy identity fields,
and each still declares the source's other views via the `info`
`DEDIREN_OEF_VIEWS_OMITTED` diagnostic (see `## Semantic Profiles`). Scope
`--views` to one view per `dediren build` invocation — with a matching
per-view `--oef-policy` — to get a correctly identified OEF per view, or fall
back to the decomposed `export` subcommand.

### Package build

Build a whole **package** — several views across several models, each with its
own render policy, presentation, and declared output path, plus view- or
model-scoped exports — in one call:

```bash
"$BUNDLE/bin/dediren" build --package package.json
# or read package.json at a directory root:
"$BUNDLE/bin/dediren" build path/to/package-dir
```

`--package` is mutually exclusive with the single-model options
(`--input`/`--out`/`--render-policy`/`--oef-policy`/`--xmi-policy`/`--emit`);
`--no-export` suppresses the export lanes. Every package-relative path resolves
against the package file's directory and is confined there.

A `package.json` (schema `package.schema.v1`) declares:

- `presentation?` — `{ lang?, dir? }`, the language and base writing direction of
  the prose the package carries, declared once for every view in it. Both reach
  each view's effective render policy as `accessibility.lang`/`.dir` and from
  there the emitted SVG root, so a package authored in one language tags all of
  its diagrams without repeating itself. A view's own render policy always wins.
  These are the only package-level presentation keys: everything narrower is
  per-view, and page-level chrome for a surrounding document is the caller's.
- `models[]` — `{ id, source }` per source model (multi-notation packages list
  several); a view binds to one via `views[].model` (optional when there is one).
- `views[]` — `{ id, model?, render_policy, presentation?, outputs }`.
  `presentation` is `{ title?, question?, diagram_kind? }`, carried and echoed;
  `title`/`question` are fed to the render lane as the SVG accessible name
  (`<title>`/`<desc>`), so each view gets its own even under a shared policy.
  `outputs` is `{ diagram, render_metadata?, layout? }` declared paths — the
  `render_metadata`/`layout` payloads are the unwrapped stage data, not `--emit`
  envelopes.
- `exports[]` — `{ id, view | model, lane, policy, output }`. Each export targets
  exactly one of a `view` (one focused file) or a whole `model` (the aggregate
  lane — one file). `lane` is `archimate-oef` or `uml-xmi`. A model-scoped
  `archimate-oef` export covers every supplied view of the model; a model-scoped
  `uml-xmi` export aggregates only its class-family views (`uml-class`,
  `uml-data`) — the same gate as single-model build's `model.uml.xml` — so
  other view kinds keep their view-scoped exports but never join the
  whole-model UMLDI aggregate, and a model with zero class-family views fails
  the export (no aggregate to produce).

Unlike single-model `build`, the package build's stdout **is** wrapped in the
standard command envelope: read `.data` for a `package-build-result` naming every
artifact at its final declared path, with one `.status` rollup and per-view /
per-export `status` and `diagnostics`. A cross-reference or declared-path
collision the package cannot satisfy is a `DEDIREN_PACKAGE_*` error before any
build begins.

Package-built diagrams and exports carry the same provenance stamps as
single-model `build` artifacts, so `dediren verify`/`dediren status` classify
them current or stale rather than unstamped (see `## Provenance & Verify`);
declared `layout`/`render_metadata` JSON outputs are stage data and stay
unstamped, like `--emit` envelopes.

## Diff & Query

Two read-only model-intelligence commands. Both consume a validated source
model, print a standard envelope on stdout, and sort every list by id, so the
same inputs always produce byte-identical output.

`dediren diff --old old.json --new new.json` compares two revisions of a
source model, keyed on stable ids. The `data` payload
(`diff-result.schema.v1`, `schemas/diff-result.schema.json`) carries `nodes`,
`relationships` (each `{added, removed, changed}`; changed entries list
field-level `{field, from, to}` where `field` is `type`, `label`, `source`,
`target`, or `properties.<key>`), and `views` (`added`/`removed` ids plus
per-view membership changes). Both inputs must be valid current-schema models
— a stale side fails with the version-gate envelope. A diff is a report,
never a merge: nothing is written.

`dediren query --kind <kind> --input model.json` answers one fixed question
(`query-result.schema.v1`, `schemas/query-result.schema.json`):

- `dependents` (requires `--id <node-id>`): fan-in (`inbound` — relationships
  targeting the node, each `{relationship_id, type, node_id}`) and fan-out
  (`outbound`).
- `orphans`: `relationship_orphans` (nodes with no incident relationships)
  and `view_orphans` (nodes referenced by no view).
- `view-coverage`: per-view node/relationship counts, the model totals, and
  `uncovered_node_ids` — the nodes no view shows.

The vocabulary is fixed by design; an unsupported `--kind`, a missing `--id`,
or an unknown node id is a `DEDIREN_COMMAND_INPUT_INVALID` usage envelope
(exit 2).

## Provenance & Verify

Every artifact `dediren build` writes carries a deterministic provenance
stamp: an inert `<metadata id="dediren-provenance">` element in SVG and a
leading `<!-- dediren-provenance … -->` comment in OEF/XMI, holding compact
JSON — `model_schema_version`, `model_sha256` (the SHA-256 of the assembled
model's canonical JSON: keys sorted, compact, UTF-8, so formatting and key
order never change it), `view_id`, the lane policy's hash
(`render_policy_sha256` / `oef_policy_sha256` / `xmi_policy_sha256`), and
`dediren_version`. Never a timestamp — identical inputs produce byte-identical
stamped artifacts. Only `build` stamps; decomposed per-stage outputs are
unstamped (the render stage never sees the source model).

`dediren verify --input model.json --artifacts <dir>` recomputes the model
hash and classifies every `.svg`/`.xml`/`.xmi` under the directory
(`schemas/verify-result.schema.json`): `current`, `stale` (error
`DEDIREN_ARTIFACT_STALE`, exit 2 — the CI drift gate: "diagrams may not be
stale on main" becomes one command), or `unstamped` (warning
`DEDIREN_ARTIFACT_UNSTAMPED`, exit 0).

`dediren status --root <dir>` is the read-only workspace freshness index
(`schemas/status-result.schema.json`): every model document with its
canonical hash, every recognized artifact classified against the models
actually present. An index, not a gate — `verify` is the exit-decidable
check.

## Render Policy Options

The render policy owns SVG presentation. Beyond `accessibility` (above), these
options shape output:

- **Edge label backing.** Edge labels default to outlined text; set
  `style.edge.label_presentation` to `background` for a filled label backing.
- **Generic node shapes.** For generic (non-notation) graphs, set `style.node.shape`
  or a per-node/type `shape` override to `rectangle`, `rounded_rectangle`
  (default), `ellipse`, `circle`, `diamond`, `hexagon`, `parallelogram`,
  `stadium`, `cylinder`, or `triangle`. A `shape` is rejected under the
  `archimate`/`uml` profiles or alongside a notation `decorator` — those notations
  fix their own geometry.
- **Colour & opacity.** Colours accept hex (`#RGB`…`#RRGGBBAA`), `rgb()`/`rgba()`,
  and CSS colour names. `fill_opacity`/`stroke_opacity` (0–1) fade node and group
  fills/strokes; edges take `stroke_opacity`; `background.fill_opacity` fades the
  page. Node/group fills can be a `fill_gradient` (`type` linear/radial, `angle`,
  `stops`).
- **Line style.** Edges and node/group borders take `line_style`
  (`solid`/`dashed`/`dotted`) and a custom `dash_pattern` array of 1–8 positive
  lengths (e.g. `[4, 2]`), the pattern winning over the preset.
- **Typography.** Global `font.weight`/`font.style` (bold/italic); per-element
  `font_weight`, `font_style`, `font_family`, `label_align` (node/group labels),
  and `label_opacity` on node/group/edge labels.
- **UML association-end adornments.** In UML class diagrams, multiplicity and
  role carried in render metadata (`properties.uml.{source,target}_multiplicity`
  and `properties.uml.{source,target}_role`) are drawn beside their own end of
  the edge, each wrapped in a
  `data-dediren-edge-adornment="<source|target>_<multiplicity|role>"` group so
  consumers can find them.

## UML Sequence Handoff

Use `fixtures/source/valid-uml-sequence-basic.json` for the sequence MVP
shape: one `Interaction`, `Lifeline` nodes, and ordered `Message`
relationships with `properties.uml.sequence` plus `message_sort`. The SVG
sequence path needs generated render metadata — missing metadata fails with
`DEDIREN_RENDER_METADATA_REQUIRED` and mismatched metadata with
`DEDIREN_RENDER_METADATA_PROFILE_MISMATCH`; regenerate through `project` rather
than hand-editing. A render policy that uses type overrides without declaring
`semantic_profile` fails with `DEDIREN_RENDER_METADATA_PROFILE_REQUIRED`
instead — add `semantic_profile` to the render policy, not the metadata. For
combined fragments, use
`fixtures/source/valid-uml-sequence-fragments.json` and
`--view sequence-fragments-view`; author `CombinedFragment` and
`InteractionOperand` nodes under `properties.uml` for `alt`, `opt`, `loop`,
and `par`. Keep message `sequence` values unique within an interaction, keep
each operand's `fragments` list in sequence order, and do not leave standalone
messages inside a combined fragment's owned sequence span.

`message_sort` drives the line: `reply` and `createMessage` draw dashed, every
other sort solid. `deleteMessage` draws its destruction cross but keeps the
ordinary arrowhead rather than the filled one §17.4 shows.

An `ExecutionSpecification` is the activation bar: it names the lifeline it
`covered`s and the `start` / `finish` **messages** that bound it. The export
resolves each bound to the occurrence on that lifeline — the receive event when
the message arrives there, the send event when it leaves — and emits
`uml:BehaviorExecutionSpecification`. Use
`fixtures/source/valid-uml-sequence-lifecycle.json` for a template carrying one
alongside a `createMessage` and a `deleteMessage`.

A `deleteMessage` targets a `DestructionOccurrenceSpecification` node naming the
`covered` lifeline it destroys, and nothing on that lifeline may follow it —
§17.12.6.4 makes the destruction the last event on its lifeline, so a later
message at either end is rejected. Other lifelines carry on unaffected.

Two rules here are **dediren's, not UML's**, and are reported as `warn`
`DEDIREN_UML_SEQUENCE_HOUSE_RULE` rather than enforced: a nested fragment's
`covered` lifelines being contained by its parent's, and a combined fragment's
owned messages being contiguous in sequence. UML's ordering is partial and
permits interleavings both rules describe — `critical` exists precisely because
ordinary fragments are not protected from interleaving — so a model that trips
either is legal and is exported as authored. The warning says the sequence
layout assumes otherwise and the diagram may not read as intended.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view sequence-view \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  > sequence-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view sequence-view \
  --input "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  > sequence-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input sequence-layout-request.json \
  > sequence-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata sequence-render-metadata.json \
  --input sequence-layout-result.json \
  > sequence-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-sequence-basic.json" \
  --layout sequence-layout-result.json \
  > sequence-xmi-result.json
```

Read `.status`, `.data`, and `.diagnostics[]` from stdout JSON envelopes for
each command before continuing. The sequence MVP supports `Interaction`,
`Lifeline`, `Message`, `ExecutionSpecification`, `Gate`, and
`DestructionOccurrenceSpecification` plus `CombinedFragment` and
`InteractionOperand`; message sorts are `synchCall`, `asynchCall`,
`asynchSignal`, `reply`, `createMessage`, and `deleteMessage`. `InteractionUse`,
`GeneralOrdering`, `ignore`, `consider`, and UMLDI are not yet supported.

## UML State Machine Handoff

Use `fixtures/source/valid-uml-state-machine-basic.json` for the state-machine
MVP. `StateMachine` and `Region` are semantic-backed groups in
`plugins.generic-graph.views[].groups` with `semantic_source_id`; state
vertices are nodes and transitions are relationships.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view state-machine-view \
  --input "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  > state-machine-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input state-machine-layout-request.json \
  > state-machine-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata state-machine-render-metadata.json \
  --input state-machine-layout-result.json \
  > state-machine-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-state-machine-basic.json" \
  --layout state-machine-layout-result.json \
  > state-machine-xmi-result.json
```

Supported vocabulary: `StateMachine`, `Region`, `State`, `FinalState`,
`Pseudostate`, `Transition`. Pseudostate kinds: `initial`, `deepHistory`,
`shallowHistory`, `join`, `fork`, `junction`, `choice`, `entryPoint`,
`exitPoint`, `terminate`. Transition kinds: `internal`, `local`, `external`.

A `Region` names its `state_machine`; a `State` never owns Regions, so
**composite states are not expressible** and neither is anything defined in terms
of them. A Transition may connect vertices in different Regions of the same
StateMachine (§14.5.11.8 constrains `containingStateMachine()`, not the Region),
but not across StateMachines. Validated: the §14.5.6.7 pseudostate degree rules
(a `fork` takes one incoming and at least two outgoing, a `join` the reverse, a
`junction` or `choice` at least one of each, an `initial` or history vertex at
most one outgoing), §14.5.8.6 region cardinality (at most one `initial` and one
of each history kind per Region), and §14.5.11.8's `state_is_internal` — an
`internal` transition must have a `State` source and equal endpoints.

Not validated, because each turns on composite states: the `local` and `external`
constraints of §14.5.11.8, the fork/join segment guard rules, and the
`entryPoint`/`exitPoint`/`terminate` degree constraints. Declaring
`kind: "local"` or `"external"` is accepted without its clause being checked.

Deferred/non-goals: `ConnectionPointReference`, `ProtocolStateMachine`,
`ProtocolTransition`, submachine states, composite and orthogonal multi-region
states, trigger event metaclasses, effects as behavior nodes, and UMLDI.

## UML Use Case Handoff

Use `fixtures/source/valid-uml-use-case-basic.json` for the use-case MVP.
Author `Actor`, `UseCase`, and `ExtensionPoint` nodes; actor `Association`
relationships; and `Include` or `Extend` relationships between use cases. Model
the subject boundary as a semantic-backed view group whose `semantic_source_id`
points at a UML structural classifier node. Put `UseCase.properties.uml.subject`
on use cases and `ExtensionPoint.properties.uml.use_case` on extension points.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view use-case-view \
  --input "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json" \
  > use-case-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view use-case-view \
  --input "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json" \
  > use-case-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input use-case-layout-request.json \
  > use-case-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata use-case-render-metadata.json \
  --input use-case-layout-result.json \
  > use-case-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-use-case-basic.json" \
  --layout use-case-layout-result.json \
  > use-case-xmi-result.json
```

Rules: `Include` and `Extend` are `UseCase -> UseCase`.
`Extend.properties.uml.extension_point`, when present, must reference an
extension point owned by the extended target use case. Deferred/non-goals:
use-case generalization, collaboration use-case realizations, and UMLDI.

## UML Component Handoff

Use `fixtures/source/valid-uml-component-basic.json` for the component MVP.
Author `Component` and `Port` nodes alongside `Package`, `Interface`, and
`Class` classifiers. Put `Port.properties.uml.component` on each port; optional
`provided` and `required` arrays reference interface ids. Use `Usage`,
`Realization`, and `Dependency` relationships, and model package/component
boundaries as semantic-backed view groups.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-component-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view component-view \
  --input "$BUNDLE/fixtures/source/valid-uml-component-basic.json" \
  > component-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view component-view \
  --input "$BUNDLE/fixtures/source/valid-uml-component-basic.json" \
  > component-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input component-layout-request.json \
  > component-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata component-render-metadata.json \
  --input component-layout-result.json \
  > component-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-component-basic.json" \
  --layout component-layout-result.json \
  > component-xmi-result.json
```

Rules: `Port.properties.uml.component` must reference a `Component`; `provided`
and `required` entries must reference `Interface` nodes. Deferred/non-goals:
composite structure, connectors, collaborations, and UMLDI.

## UML Deployment Handoff

Use `fixtures/source/valid-uml-deployment-basic.json` for the deployment MVP.
Author `Node`, `Device`, `ExecutionEnvironment`, `Artifact`, and
`DeploymentSpecification` nodes alongside manifested structural classifiers.
Put optional `ExecutionEnvironment.properties.uml.node` on nested runtimes. Use
`Deployment`, `Manifestation`, and `CommunicationPath` relationships, and model
deployment target boundaries as semantic-backed view groups.

```bash
"$BUNDLE/bin/dediren" validate \
  --plugin generic-graph \
  --profile uml \
  --input "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view deployment-view \
  --input "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json" \
  > deployment-layout-request.json

"$BUNDLE/bin/dediren" project \
  --target render-metadata \
  --plugin generic-graph \
  --view deployment-view \
  --input "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json" \
  > deployment-render-metadata.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input deployment-layout-request.json \
  > deployment-layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/uml-svg.json" \
  --metadata deployment-render-metadata.json \
  --input deployment-layout-result.json \
  > deployment-render-result.json

"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-deployment-basic.json" \
  --layout deployment-layout-result.json \
  > deployment-xmi-result.json
```

Rules: `Deployment` connects an `Artifact` or `DeploymentSpecification` to a
deployment target; `Manifestation` connects an artifact or deployment
specification to a structural classifier; `CommunicationPath` connects
deployment targets. Deferred/non-goals: full nested part/property modeling,
deployment slots, and UMLDI.

## Runtime Probes

```bash
VERSION=2026.08.4
BUNDLE=/tmp/dediren-dist/dediren-agent-bundle-${VERSION}

"$BUNDLE/bin/dediren" --version

"$BUNDLE/bin/dediren" build \
  --input "$BUNDLE/fixtures/source/valid-basic.json" \
  --out /tmp/dediren-probe-out \
  --render-policy "$BUNDLE/fixtures/render-policy/default-svg.json"
```

`--version` prints the product banner; a one-shot `build` against a bundled
fixture exercises the whole in-process pipeline (project → layout →
validate-layout → render) end to end and is the readiness probe for the bundle.
Workflow commands return command envelopes using `schemas/envelope.schema.json`.
The single packaged `dediren` launcher sets `DEDIREN_BUNDLE_ROOT` automatically
so commands can run from any current working directory.

## Bundle Smoke Workflow

```bash
"$BUNDLE/bin/dediren" validate \
  --input "$BUNDLE/fixtures/source/valid-basic.json"

"$BUNDLE/bin/dediren" project \
  --target layout-request \
  --plugin generic-graph \
  --view main \
  --input "$BUNDLE/fixtures/source/valid-basic.json" \
  > layout-request.json

"$BUNDLE/bin/dediren" layout \
  --plugin elk-layout \
  --input layout-request.json \
  > layout-result.json

"$BUNDLE/bin/dediren" validate-layout \
  --input layout-result.json

"$BUNDLE/bin/dediren" render \
  --plugin render \
  --policy "$BUNDLE/fixtures/render-policy/default-svg.json" \
  --input layout-result.json \
  > render-result.json
```

`validate-layout` quality fields: `overlap_count`, `connector_through_node_count`
(counts a segment crossing any node's interior past a 1.5px inset, including its
own source/target node — self-loops and UML lifelines are excluded, since a
Message legitimately anchors to the lifeline axis inside the head box),
`invalid_route_count`, `route_detour_count`, `route_close_parallel_count`,
`group_boundary_issue_count`, `group_label_band_issue_count`,
`label_space_issue_count`, `edge_label_dissociation_count`,
`edge_crossing_count` (informational only), and
`warning_count`. The payload `data.status` is `ok` only when all
non-informational counts and warnings are zero; otherwise it is `warning`, and
the command envelope now restates that verdict so consumers reading only
`.status`/`.diagnostics[]` see it: envelope `status` becomes `warning` and one
`DEDIREN_LAYOUT_QUALITY_WARNING` diagnostic (severity `warning`, `path` pointing
at the offending `data.*` count) is emitted per nonzero non-informational count.
A warning verdict is not a failure — the exit code stays `0`. ArchiMate junction
nodes detached from an incident edge route fail with
`DEDIREN_LAYOUT_JUNCTION_OFF_INCIDENT_ROUTE`.

Hard-error layout diagnostics (severity `error`) additionally carry an optional
`source_pointer` — a JSON-Pointer into the source model (for example `/nodes/3`
or `/relationships/2`) naming the element to repair. Use it to jump straight
from a layout-quality failure to the source node or relationship that caused it.

The `elk-layout` plugin uses official Eclipse ELK Java libraries and requires
Java 21 or newer. It does not use external layout adapters. Use
`layout_preferences.mode: "flow"` for directed diagrams that need ELK Layered
placement and routing. Use `layout_preferences.mode: "packed"` only for
edge-less node/group maps; this selects official ELK Rectangle Packing and
returns no edge routes. The `algorithm` option selects the layout algorithm
(`layered`, the default, is currently the only value). `routing.style` accepts
`orthogonal` (default), `polyline`, or `spline`. Layered phase strategies
(`cycle_breaking`, `layering.strategy`, `crossing.strategy`,
`crossing.greedy_switch`, `placement.strategy`), graph tuning (`compaction`,
`components`, `high_degree_nodes`, `thoroughness`), per-node placement hints
(`layer_constraint`, `partition`), and per-edge priority hints
(`resist_reversal`, `keep_short`, `keep_straight`) are also configurable under
`layout_preferences`; see `schemas/layout-request.schema.json` for the allowed
values.

The omitted-preference `compact` baseline is calibrated for Dediren's labels
and ports (40 node–node and 24 edge/port clearances) and omits redundant
collinear route points. Re-run `layout` to adopt those defaults; do not copy
coordinates from checked-in layout or render goldens, which are regression
evidence rather than authoring templates.

## Export

ArchiMate OEF:

```bash
"$BUNDLE/bin/dediren" export \
  --plugin archimate-oef \
  --policy "$BUNDLE/fixtures/export-policy/default-oef.json" \
  --source "$BUNDLE/fixtures/source/valid-archimate-oef.json" \
  --layout "$BUNDLE/fixtures/layout-result/archimate-oef-basic.json" \
  > oef-result.json
```

UML/XMI:

```bash
"$BUNDLE/bin/dediren" export \
  --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-basic.json" \
  --layout "$BUNDLE/fixtures/layout-result/uml-basic.json" \
  > xmi-result.json
```

Export envelopes carry one artifact directly at `.data.artifact_kind` and
`.data.content` — unlike render's `.data.artifacts[]` array:

```bash
jq -r '.data.content' oef-result.json > model-oef.xml
```

The default export policies hard-code fixture identity: `default-oef.json`
sets `model_identifier: "id-dediren-oef-basic-model"` and
`model_name: "Dediren OEF Basic"`. Export succeeds with them unchanged, but
the envelope carries the warning `DEDIREN_EXPORT_IDENTITY_PLACEHOLDER`
(`status: warning`, exit still 0) so a mis-identified deliverable cannot ship
silently — copy the policy and replace the identity fields for a real model.
Check the copied policy early with `dediren validate --input my-policy.json`
(or `dediren_validate`): the document's schema-version field selects the
family, so a stale or malformed policy fails here instead of at build time:

```json
{
  "oef_export_policy_schema_version": "oef-export-policy.schema.v1",
  "model_identifier": "id-my-model",
  "model_name": "My Model",
  "view_identifier": "id-view-main",
  "view_name": "Main",
  "viewpoint": "Application Cooperation"
}
```

Use `DEDIREN_OEF_SCHEMA_DIR` or `DEDIREN_XMI_SCHEMA_PATH` for offline schema
validation. `DEDIREN_OEF_SCHEMA_DIR` must point at a flat directory containing
the ArchiMate 3.1 XSDs (`archimate3_Model.xsd`, `archimate3_View.xsd`,
`archimate3_Diagram.xsd`) and — for the real Open Group set — the W3C
`xml.xsd` they import; `DEDIREN_XMI_SCHEMA_PATH` points at the XMI 2.5.1
`XMI.xsd` file itself. Use `DEDIREN_SCHEMA_CACHE_DIR` when downloads are
allowed and a stable cache location is desired; one online run populates a
reusable offline cache (subtrees `opengroup/archimate/3.1/` and
`omg/xmi/2.5.1/`, which also satisfy the two offline variables). Give these
paths as absolute: plugins run from the bundle's product root, so a relative
value resolves against that root rather than your current directory.

Online schema downloads use Java's HTTP client: HTTPS is required before and
after redirects, the connect timeout is 20 seconds, each request timeout is 60
seconds, and a response body above 8 MiB is rejected before it can replace a
cached file. Redirect handling refuses HTTPS-to-HTTP downgrade.

When schema downloads must go through a proxy, set `HTTPS_PROXY`, `HTTP_PROXY`,
or `ALL_PROXY`, plus optional `NO_PROXY` (lowercase forms are also accepted).
For an HTTPS schema URL, precedence is `HTTPS_PROXY`, then `HTTP_PROXY`, then
`ALL_PROXY`; within each name the lowercase value wins when both cases exist.
`NO_PROXY` accepts comma-separated exact hosts, leading-dot domain suffixes,
or `*`. Proxy URIs must be credential-free `http` URIs, include a host, and
contain no path, query, or fragment; secure proxy transport and proxy-URI
credentials are not supported. Any selected invalid proxy fails closed with a
credential-free schema-fetch error instead of attempting a direct connection.
If a download still fails, the
`DEDIREN_OEF_SCHEMA_UNAVAILABLE` / `DEDIREN_XMI_SCHEMA_UNAVAILABLE` diagnostic
message names both the proxy variables and the offline schema-path fallback, so
you can recover from stdout JSON alone.

## Repair Rules

- `DEDIREN_SCHEMA_INVALID`: validate against `schemas/model.schema.json`. A
  common cause is authored geometry (`x`, `y`, `width`, `height`) or other
  fields the schema rejects on a node — source JSON is semantic only, so remove
  them.
- `DEDIREN_DUPLICATE_ID`: make node and relationship ids unique.
- `DEDIREN_GENERIC_GRAPH_DUPLICATE_VIEW_ID` /
  `DEDIREN_GENERIC_GRAPH_DUPLICATE_GROUP_ID`: rename the colliding view id, or
  the colliding group id within its view — group ids are scoped per view.
- `DEDIREN_PACKAGE_DUPLICATE_ID`: two entries in the named package id space
  (`models[]`, `views[]`, or `exports[]`) share the quoted id. Rename one so
  every package id space is duplicate-free.
- `DEDIREN_DANGLING_ENDPOINT`: repair relationship source/target ids or include
  the missing node.
- `DEDIREN_INPUT_FILE_TOO_LARGE`: an input file (source model, fragment, or
  policy) exceeds the 64 MiB input ceiling; the message carries the actual and
  maximum byte counts. Split the model into fragments or shrink the file.
- `DEDIREN_SOURCE_FRAGMENT_LIMIT_EXCEEDED`: the source declares more than 1000
  fragments. Consolidate fragments.
- `DEDIREN_SOURCE_ELEMENT_LIMIT_EXCEEDED`: the merged model exceeds 100000
  nodes plus relationships. Split it into separate models.
- `DEDIREN_MERMAID_SYNTAX_INVALID`: repair the syntax at the reported 1-based
  line and column within the supported flowchart subset.
- `DEDIREN_MERMAID_UNSUPPORTED_DIAGRAM` /
  `DEDIREN_MERMAID_UNSUPPORTED_CONSTRUCT` /
  `DEDIREN_MERMAID_UNSUPPORTED_EDGE`: convert the input to one supported
  `flowchart`/`graph`, remove the unsafe or ambiguous construct, or replace the
  edge with a solid directed `-->` edge. Import is atomic; no partial data was
  produced.
- `DEDIREN_MERMAID_INPUT_TOO_LARGE` /
  `DEDIREN_MERMAID_STATEMENT_LIMIT_EXCEEDED` /
  `DEDIREN_MERMAID_ELEMENT_LIMIT_EXCEEDED` /
  `DEDIREN_MERMAID_NESTING_LIMIT_EXCEEDED` /
  `DEDIREN_MERMAID_TOKEN_LIMIT_EXCEEDED`: split or simplify the diagram below
  the ceiling stated in the diagnostic and in `## Mermaid Flowchart Import`.
- `DEDIREN_MERMAID_HINT_IGNORED`: import succeeded, but the named presentation
  or subgraph-layout hints were intentionally discarded. Reapply appearance
  through Dediren render policy if needed.
- `DEDIREN_DOT_SYNTAX_INVALID`: repair the syntax at the reported 1-based line
  and column within the input.
- `DEDIREN_DOT_UNSUPPORTED_CONSTRUCT`: HTML-like labels, ports and compass
  points, and subgraph-shorthand edges are not supported; rewrite the input
  without them.
- `DEDIREN_DOT_INPUT_TOO_LARGE` /
  `DEDIREN_DOT_STATEMENT_LIMIT_EXCEEDED` /
  `DEDIREN_DOT_ELEMENT_LIMIT_EXCEEDED` /
  `DEDIREN_DOT_NESTING_LIMIT_EXCEEDED` /
  `DEDIREN_DOT_TOKEN_LIMIT_EXCEEDED`: split or simplify the diagram below
  the ceiling stated in the diagnostic and in `## DOT Graph Import`.
- `DEDIREN_DOT_HINT_IGNORED`: import succeeded, but the named presentation
  attributes were intentionally discarded. Reapply appearance through Dediren
  render policy if needed.
- `DEDIREN_GENERIC_GRAPH_PLUGIN_REQUIRED`: the source has no
  `plugins.generic-graph` object. Add it with a `views` array (see
  `## Minimal Source JSON`) — every semantic-validate, project, and build call
  needs it.
- `DEDIREN_GENERIC_GRAPH_VIEW_UNKNOWN`: the requested view id is not declared
  in `plugins.generic-graph.views`. Fix the `--view`/`--views` value or add
  the missing view to the source.
- `DEDIREN_GENERIC_GRAPH_VIEW_NODE_UNKNOWN`: a `views[].nodes` entry names a
  node id absent from the source `nodes`. Fix the typo or add the missing
  node.
- `DEDIREN_GENERIC_GRAPH_VIEW_RELATIONSHIP_UNKNOWN`: a `views[].relationships`
  entry names a relationship id absent from the source `relationships`. Fix
  the typo or add the missing relationship.
- `DEDIREN_GENERIC_GRAPH_GROUP_MEMBER_OUTSIDE_VIEW`: a group `members` entry
  is neither one of that view's `nodes` nor another group id in the same view.
  Add it to the view or remove it from the group.
- `DEDIREN_GENERIC_GRAPH_GROUP_SEMANTIC_SOURCE_UNKNOWN`: a group's
  `semantic_source_id` names no source node. Fix the id, add the backing node,
  or drop `semantic_source_id`.
- `DEDIREN_COMMAND_TARGET_UNSUPPORTED`: the `project --target` value is
  outside the accepted set — use `layout-request` or `render-metadata`.
- `DEDIREN_PLUGIN_UNKNOWN`: unknown engine id — the bundled set is
  `mermaid`, `generic-graph`, `elk-layout`, `render`, `archimate-oef`, `uml-xmi`. Fix the
  `--plugin` value.
- `DEDIREN_PLUGIN_UNSUPPORTED_CAPABILITY`: the engine id exists but not for
  this command's capability (for example asking `elk-layout` to render). Fix
  the `--plugin` value for this command.
- `DEDIREN_ELK_PACKED_OPTION_IGNORED`: a `layout_preferences.mode: "packed"`
  request carried layered-only options/hints the rectangle-packing algorithm
  ignores (a `warning`; the layout succeeded). The message lists their JSON
  pointers — delete those fields, or drop `mode: "packed"` so the layered
  algorithm honors them.
- `DEDIREN_RENDER_METADATA_PROFILE_NOT_APPLIED`: the render metadata declares
  `semantic_profile` `uml` or `archimate` but the render policy declares none,
  so that notation's shapes, decorators, and label placement were not applied
  (a `warning`; the SVG was rendered). Layout already sized the notation's
  symbol nodes — a UML `DecisionNode` or `Port` is a fixed ~32px glyph whose
  label belongs outside it — so a generic paint of that geometry puts labels
  over symbols too small to hold them. Add `semantic_profile` to the render
  policy (see `## Render`), or ignore it if a deliberately generic rendering of
  a notation view is what you want.
- `DEDIREN_EXPORT_SCHEMA_CONFORMANCE`: informational (`info`, rides an `ok`
  envelope) — names exactly which standards schema the export was validated
  against and its provenance (pinned SHA-256-verified download, or the
  user-supplied schema path/directory). No action needed.
- `DEDIREN_EXPORT_IDENTITY_PLACEHOLDER`: the export policy still carries the
  shipped fixture identity (a `warning`; the artifact was produced and is
  otherwise valid). Copy the default policy and replace its identity fields
  (see `## Export`); expected and ignorable when deliberately exporting the
  bundled fixtures.
- `DEDIREN_XMI_TYPE_NAME_AMBIGUOUS`: several selected classifiers share one
  label, so name-based attribute/parameter type references bind to the first
  (`info`; the export succeeded). The message names the winning and ignored
  node ids; rename the classifiers if the shadowed one was intended.
- `DEDIREN_ARTIFACT_STALE`: a stamped artifact's provenance no longer matches
  the model's recomputed hash — the model changed after the artifact was
  built. Rebuild the artifact (or check out the matching model revision);
  see `## Provenance & Verify`.
- `DEDIREN_ARTIFACT_UNSTAMPED`: a recognized artifact carries no provenance
  stamp, so currency cannot be decided (a `warning`). Only `dediren build`
  stamps artifacts; rebuild through `build` if you want it verifiable.
- `DEDIREN_ENGINE_FAILED`: an unexpected in-memory engine failure. Not an input
  problem — the diagnostic message names the engine; report it with the failing
  command and input rather than retrying with modified JSON. Ghost view
  references (a view or group naming a missing element) no longer surface
  here — they are the structured `DEDIREN_GENERIC_GRAPH_*` input errors above
  (exit 2).
- `DEDIREN_COMMAND_INPUT_INVALID`: the CLI could not read or parse a command
  input file.

Codes not listed in this guide are internal: `DEDIREN_ELK_*` (layout engine
internals), `DEDIREN_LAYOUT_*` (layout quality gates), `DEDIREN_GENERIC_GRAPH_*`,
`DEDIREN_ARCHIMATE_*`, `DEDIREN_UML_*` (profile and notation validation),
`DEDIREN_OEF_*` / `DEDIREN_XMI_*` (export validation), `DEDIREN_PACKAGE_*`
(package build validation), `DEDIREN_SEMANTIC_*`, `DEDIREN_VALIDATE_*`,
`DEDIREN_SVG_*`, `DEDIREN_COMMAND_*`, `DEDIREN_MCP_*`.
Their `message` and `path` are written to be self-repairing: follow the
instruction in the message, and report any such code that persists after you
have done so.

### What a green command can still cost you

Most codes above name something to fix in your input. These do not — they report
that a stage carried less than it was given, and there is nothing to repair in
the source JSON. Read them from `.diagnostics[]`; the envelope `status` stays
`ok` or `warning`, so a caller checking only the status will miss them.

- `DEDIREN_OEF_GEOMETRY_CLAMPED` (`warn`) — the exchange format types diagram
  coordinates as non-negative integers and sizes as positive integers, which is
  narrower than a layout result. A value outside that range is rounded and
  clamped, and the exported node or bendpoint sits where the format allows
  rather than where the layout put it. **The layout engine can produce such a
  value itself**, so this is not always avoidable by editing input.
- `DEDIREN_OEF_PROPERTY_FLATTENED` (`warn`) — an object or array property value
  rendered as JSON text, because the exchange format carries property values as
  text. Well-formed and valid; unrecoverable as structure on import.
- `DEDIREN_OEF_VIEWPOINT_UNKNOWN` (`warn`) — the viewpoint is outside the
  format's own vocabulary. The export is schema-valid; an importing tool will
  show it as unrecognized.
- `DEDIREN_XMI_ELEMENTS_OMITTED` / `DEDIREN_XMI_RELATIONSHIPS_OMITTED` (`info`)
  — content outside the exported view, or in-view content the UML/XMI mapping
  cannot represent. The message says which.
- `DEDIREN_OEF_VIEWS_OMITTED` (`info`) — declared views this export does not
  carry.
- `DEDIREN_ARCHIMATE_RELATIONSHIP_DEPRECATED` (`info`) — a relationship the
  standard deprecates but still permits. Today that is
  `WorkPackage -[Realization]-> Deliverable`, which §12.1 replaces with an
  access relationship. The model is legal; a future revision may remove the
  edge.
- `DEDIREN_UML_SEQUENCE_HOUSE_RULE` (`warn`) — a sequence rule dediren enforces
  that UML does not. The model is spec-legal and is exported as authored; the
  warning says the rendered diagram may not read as intended. See the two named
  in `## UML Sequence Handoff`.

Two losses have **no diagnostic at all**, because they are properties of the
mapping rather than of any one document: `out`/`inout` parameters export as
`in`, and ArchiMate `Access`/`Influence`/`Association` attributes are not
modelled. Both are described where they are authored, above.

## Migration

`DEDIREN_SCHEMA_VERSION_OUTDATED` means the file declares a schema version this
build has superseded. The diagnostic carries the fix as data: its `migration`
object holds `from`, `to`, and an ordered `operations` list of exact
JSON-Pointer edits — `rename_field` (move the value at `pointer` to `to`),
`remove_key` (delete `pointer`; absent keys are a no-op), `set_version` (set
`pointer` to `value`), or `regenerate` (do not hand-edit; re-emit the file with
its producing command, e.g. `dediren project` for a layout-request). Apply the
operations in order — you are the hands; dediren never rewrites the file — and
re-validate. The prose steps below remain the human-readable record of the
same upgrades. `DEDIREN_SCHEMA_VERSION_UNKNOWN` means the version is absent,
misspelled, or newer than this build — there is no upgrade path; fix the
version field or use a newer bundle.

Entries are keyed by schema id, not by release. A schema id changes only when
the contract changes, so it is the only durable signal of what a file needs.

### svg-render-policy.schema.v1 → render-policy.schema.v1

The family was renamed, and the version field was renamed with it. Rename the
field `svg_render_policy_schema_version` to `render_policy_schema_version` and
set its value to `render-policy.schema.v1`. Nothing else changes.

### render-policy.schema.v1 → render-policy.schema.v2

Raster output was dropped. Remove the top-level `raster` block (its `scale` and
`background` keys) and set `render_policy_schema_version` to
`render-policy.schema.v2`. There is no replacement: renders are SVG only.

### render-policy.schema.v2 → render-policy.schema.v3

Interactive SVG was retired. Remove the top-level `interactive` key (`none`,
`svg`, `html`, or `both`) and the `interaction` block under `style` (its
`highlight_stroke` and `highlight_stroke_width` keys), then set
`render_policy_schema_version` to `render-policy.schema.v3`. There is no
replacement: renders are static.

### layout-request.schema.v1 → layout-request.schema.v2

Usually not a hand edit: `dediren project` always emits the current version,
so regenerate the request unless you deliberately keep a hand-written one. To
upgrade a kept v1 file: set `layout_request_schema_version` to
`layout-request.schema.v2`. v2 adds an optional `source_pointer` (a JSON
Pointer into the source model, starting with `/`) on nodes and edges — add it
only if you track provenance — and constrains node `id` charsets and `role` to
the known role set, so rename any id the v2 schema rejects consistently across
nodes, edges, and constraints.

## Plugin Environment

The bundle launcher uses `DEDIREN_BUNDLE_ROOT` for product-root discovery. The
bundled engines run inside the CLI process; the export engines receive the
CLI's environment explicitly for the schema-path and validator-override
variables below and read nothing else. Important explicit variables:

- `DEDIREN_BUNDLE_ROOT`: explicit bundle or repository root for bundled
  schemas, fixtures, and the launcher. The packaged `dediren` launcher sets this
  automatically. If it points somewhere without `schemas/model.schema.json`, or
  discovery fails entirely, schema-touching commands emit a
  `DEDIREN_PRODUCT_ROOT_UNRESOLVED` error envelope on stdout with exit `2`.
- `DEDIREN_OEF_SCHEMA_DIR`: local OEF schema directory.
- `DEDIREN_XMI_SCHEMA_PATH`: local XMI schema file, or a driver schema that
  imports `XMI.xsd` plus a UML 2.5.1 XSD to also validate UML content (imports
  resolve local-only from the driver's own directory). Both export lanes
  validate in-JVM — no external validator binary or override variable exists.
- `DEDIREN_SCHEMA_CACHE_DIR`: cache directory for schema downloads.
- `HTTPS_PROXY`, `HTTP_PROXY`, `ALL_PROXY`, `NO_PROXY` (and their lowercase
  forms): consumed by the Java HTTP schema fetcher. For HTTPS downloads the
  proxy precedence is HTTPS, HTTP, then ALL; lowercase wins within a name, and
  an invalid selected proxy fails closed rather than bypassing it.
- `DEDIREN_LOG_LEVEL`: `trace`, `debug`, `info`, `warn`, `error`, or `off`
  (default `off`). Turns on human-readable debug logging on **stderr** for one
  run. Any other value is ignored with a note on stderr.

## Debug Logging

Logging is off by default and is a human debugging aid only — never part of the
agent contract. Everything an agent must act on is already in the stdout
envelope's `status` and `diagnostics[]`; nothing is only discoverable in a log
line, and first-party code cannot log above `debug` (an architecture rule
forbids `info`/`warn`/`error`). So do not parse logs, and do not switch logging
on to make a decision — switch it on when a human is investigating.

```bash
DEDIREN_LOG_LEVEL=debug dediren layout --plugin elk-layout --input request.json
```

```
[DEBUG] dev.dediren.core.engine.EngineDispatch - engine resolved: id=elk-layout capability=layout
[DEBUG] dev.dediren.plugins.elklayout.ElkLayoutEngine - elk layout: nodes=6 edges=6 elapsedMs=82
[DEBUG] dev.dediren.core.engine.EngineDispatch - engine ok: id=elk-layout
```

Logs go to stderr; stdout stays a clean JSON envelope, so `| jq` keeps working
with logging on. Logged lines cover engine dispatch, ELK layout size and timing,
schema-cache hits and misses, and the in-JVM schema validation step. Log output
is not a stable contract and may change between releases.

The `bin/dediren` launcher routes JVM-level log output off stdout and onto
stderr (`-Xlog:all=off:stdout -Xlog:all=warning:stderr:uptime,level,tags`), so no
VM warning (cgroup resource limits, for one) can ever land on top of the command
envelope. Keep stderr for human debugging only. Agents should decide success or
failure from stdout JSON.

## Redistribution

Preserve the bundle root `LICENSE`, `THIRD-PARTY-NOTICES.md`, and this guide
when redistributing a Dediren archive.

This file is the shipped agent-facing contract for bundle usage. If Dediren is
embedded in another agent skill, plugin, or tool package, preserve this path or
carry the same JSON authoring, command handoff, runtime probe, and repair
guidance in that package.
