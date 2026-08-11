# `dediren.dediren` — Dediren, modelled by Dediren

This is a Dediren **self-model**: the compiler's own module architecture,
authored as a Dediren package and compiled through the same
`project → layout → render/export` pipeline the product ships. It dogfoods the
tool on the most credible subject available — itself.

It is a **mixed-notation package** (`package.json`, `package.schema.v1`)
carrying one single-notation model per notation; each model declares its own
`semantic_profile`, so the manifest binds models to views without repeating it:

| View | Notation | Model | Architecture question |
| --- | --- | --- | --- |
| `module-architecture` | ArchiMate® Application Cooperation | `model.json` | Which modules make up the compiler, and how do they depend on each other? |
| `engine-seam` | UML® Class | `model-uml.json` | What typed interfaces does `engine-api` define, and which engine classes realise them? |
| `build-pipeline` | UML® Sequence | `model-sequence.json` | How does a `dediren build` turn a source model into an SVG, and optionally OEF or XMI, through the `engine-api` seam? |
| `distribution` | UML® Deployment | `model-deployment.json` | How does dediren ship and run — the single `bin/dediren` launcher and one shrink-merged `lib` jar hosting the cli and every engine in one process? |

The UML package `engine-api` carries a cross-notation handoff link
(`properties.uml.architecture_context`) to the ArchiMate `engine-api`
component: the class view *elaborates* the seam the cooperation view shows as a
single box.

## What is hand-authored vs generated

Hand-authored, checked-in source (edit these):

- `model.json`, `model-uml.json`, `model-sequence.json`,
  `model-deployment.json` — the four notation models.
- `package.json` — the dediren-native build manifest (`package.schema.v1`):
  binds models to views and exports, and carries each view's title, question and
  diagram kind. It replaced the retired `project.json` sidecar, so the whole
  package now builds in one `dediren build --package` call and the gallery
  builder reads the same manifest the build does.
- `render-policy.json` and `render-policy-uml.json` — both in the repo's Amber
  CRT theme (matching the README pipeline diagram); the UML policy accents
  interfaces in emerald and classes in amber.
- `export-policy.json` (OEF), `export-policy-uml.json` (XMI).

Reproducible output (regenerated from the source above):

- `generated/svg/*.svg` — the rendered diagrams (committed; embedded in the root
  README and inlined into `gallery.html`).
- `generated/export/dediren.oef.xml` — ArchiMate Open Exchange Format export.
- `generated/export/dediren.uml.xmi` — UML 2.5.1 XMI export.
- `gallery.html` — a self-contained, zoomable, notation-grouped viewer over all
  four SVGs. Rebuild it with the skill's `build-gallery.py <package>` as the next
  action after any re-render (`--check` drift-checks it); it reads `package.json`
  and the committed render-metadata.
- `generated/render-metadata/*.json` — per-view marker metadata (committed; the
  gallery builder reads it to rebuild and drift-check `gallery.html`).
- `generated/layout/` — intermediate ELK geometry (git-ignored; recreated on
  every build).

## Regenerate

From a built bundle (the glob picks the newest bundle under `dist/`, so it is
version-agnostic; if `dist/` is empty, build one with
`./mvnw -pl dist-tool -am verify -Pdist-build`):

```bash
BUNDLE=$(ls -d dist/dediren-agent-bundle-* | grep -v '\.tar' | sort | tail -1)
PKG=docs/architecture/dediren.dediren

# every view and export, straight to the paths package.json declares
"$BUNDLE/bin/dediren" build --package "$PKG/package.json"
```

Use the bundle built from *this* working tree, not an installed release: the
diagrams must show what the current renderer produces, and a released `dediren`
can be several geometry changes behind while still satisfying every
compatibility floor.

The build writes each view's SVG, render-metadata and export directly to its
declared output — no copying. Each committed SVG then gets the skill's
`svg-accessible-name.py` post-render step (title = the view's `presentation.title`,
desc = its `presentation.question`), which adds a `role="img"`/`<title>`/`<desc>`
accessible name plus a visible title band. Re-running the build strips the band,
so re-apply it after every regeneration and before rebuilding the gallery. The
band is height-synced to the expanded `viewBox` (so browsers do not letterbox
the diagram) and painted with the diagram's own background colour and a
contrasting title fill, so it stays readable on the dark Amber CRT canvas.

## Modelling decisions (disclosed)

- **Modules are ArchiMate `ApplicationComponent`s.** Each of the 17 shipped
  Maven modules is one component. `test-support` and `coverage-report` are
  test/build-only and are intentionally excluded from the product architecture.
- **Dependencies are `Serving` relationships, drawn provider → consumer.** A
  Maven compile/runtime dependency `A → B` means B *serves* A, so the arrow runs
  from the depended-upon module toward the module that needs it. The stable
  `contracts` kernel therefore serves nearly everything (and is accented in
  emerald as the root). This is the *opposite* of the "arrow points at what you
  depend on" intuition — the four stability-tier bands, not the arrow heads,
  carry the "modular monolith rooted at `contracts`" reading.
- **The model holds all 51 direct dependency edges; the hero view shows 23.**
  Fidelity lives in `model.json` (every compile/runtime edge, used by the OEF
  export). The `module-architecture` *view* curates to the architecturally
  significant edges — omitting the ubiquitous `contracts`/`ir` edges every
  module transitively carries, and the `cli → engine` EngineWiring fan-in (that
  wiring is the subject of the `engine-seam` view). The full edge table is
  [`docs/architecture-guidelines.md`](../../architecture-guidelines.md) §2.
- **Tier bands are layout-only groups**, not ArchiMate `Grouping` elements.
- **Evidence:** every node and edge is `source-backed`, extracted from each
  module's `pom.xml` and the guidelines' allowed-edge table. No
  architect-owned or low-confidence content.

## Layout note

The `module-architecture` view is laid out top-to-bottom (`direction: down`), so
the four stability tiers read as stacked bands, with orthogonal edge routing and
endpoint merging **off** (off so each dependency shows distinctly — a merged
arrow would hide that, e.g., `render` has three incoming dependencies). The view
passes `validate-layout` with no quality warning.

The tier boxes are `role: "layout-only"` groups. The layout engine draws such a
group as a **partition-aligned band with a bounding box** over its members — not
as an ELK compound node — so every cross-tier dependency routes node-to-node on
the flat graph and stays individually followable, while the labelled tier bands
still frame the reading. (A `SEMANTIC_BOUNDARY` group, a real containment, keeps
the nested-hierarchy layout, where routing edges through the boundary is
correct.)
