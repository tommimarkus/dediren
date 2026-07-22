# `dediren.dediren` — Dediren, modelled by Dediren

This is a Dediren **self-model**: the compiler's own module architecture,
authored as a Dediren package and compiled through the same
`project → layout → render/export` pipeline the product ships. It dogfoods the
tool on the most credible subject available — itself.

It is a **mixed-notation package** (`project.json` schema
`souroldgeezer.architecture.dediren.project.v2`) carrying one single-notation
model per notation:

| View | Notation | Model | Architecture question |
| --- | --- | --- | --- |
| `module-architecture` | ArchiMate® Application Cooperation | `model.json` | Which modules make up the compiler, and how do they depend on each other? |
| `engine-seam` | UML® Class | `model-uml.json` | What typed interfaces does `engine-api` define, and which engine classes realise them? |

The UML package `engine-api` carries a cross-notation handoff link
(`properties.uml.architecture_context`) to the ArchiMate `engine-api`
component: the class view *elaborates* the seam the cooperation view shows as a
single box.

## What is hand-authored vs generated

Hand-authored, checked-in source (edit these):

- `model.json`, `model-uml.json` — the two notation models.
- `project.json` — binds models to views and exports (v2 multi-model layout).
- `render-policy.json` and `render-policy-uml.json` — both in the repo's Amber
  CRT theme (matching the README pipeline diagram); the UML policy accents
  interfaces in emerald and classes in amber.
- `export-policy.json` (OEF), `export-policy-uml.json` (XMI).

Reproducible output (regenerated from the source above):

- `generated/svg/*.svg` — the rendered diagrams (committed; embedded in the root
  README and inlined into `gallery.html`).
- `generated/export/dediren.oef.xml` — ArchiMate Open Exchange Format export.
- `generated/export/dediren.uml.xmi` — UML 2.5.1 XMI export.
- `gallery.html` — a self-contained, zoomable, notation-grouped viewer over both
  SVGs.
- `generated/render-metadata/*.json` — per-view marker metadata (committed; the
  gallery builder reads it to rebuild and drift-check `gallery.html`).
- `generated/layout/` — intermediate ELK geometry (git-ignored; recreated on
  every build).

## Regenerate

From a built bundle (the glob picks the newest bundle under `dist/`, so it is
version-agnostic; if `dist/` is empty, build one with
`./mvnw -pl dist-tool -am verify -Pdist-build`):

```bash
BUNDLE=$(ls -d dist/dediren-agent-bundle-* | grep -v '\.tar\.gz$' | sort | tail -1)
PKG=docs/architecture/dediren.dediren

# ArchiMate module-architecture view → SVG + OEF
"$BUNDLE/bin/dediren" build --input "$PKG/model.json" --view module-architecture \
  --render-policy "$PKG/render-policy.json" --oef-policy "$PKG/export-policy.json" \
  --out out/self-model

# UML engine-seam view → SVG + XMI
"$BUNDLE/bin/dediren" build --input "$PKG/model-uml.json" --view engine-seam \
  --render-policy "$PKG/render-policy-uml.json" --xmi-policy "$PKG/export-policy-uml.json" \
  --out out/self-model
```

Each view writes under `out/self-model/<view-id>/` (`diagram.svg`, `oef.xml`,
`xmi.xml`); copy the SVGs and exports into `generated/`. The committed SVGs then
get the skill's `svg-accessible-name.sh` post-render step, which adds a
`role="img"`/`<title>`/`<desc>` accessible name plus a visible title band. The
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

## Known layout note

The `module-architecture` view carries a `route_close_parallel_count: 25`
layout-quality warning — inherent to the `engine-api` seam's 8-way fan-out and
unchanged by density/endpoint-merging tuning. The routes render clearly
separated; the warning is disclosed rather than suppressed.
