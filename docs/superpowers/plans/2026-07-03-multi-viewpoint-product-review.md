# Multi-Viewpoint Product Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Execute the staged multi-viewpoint product review defined in
`docs/superpowers/specs/2026-07-03-multi-viewpoint-product-review-design.md`:
groundwork → four role-isolated reviewer agents → verification → consolidated
report + follow-up plans.

**Architecture:** Shared groundwork builds one clean bundle and scaled models;
four reviewer subagents (cold-start agent, plugin author, maintainer-2027,
performance) run in parallel against those artifacts and return structured
findings JSON; verifier subagents reproduce every qualitative finding;
synthesis lands as a committed report plus drafted follow-up plans. This is an
orchestration plan, not a code plan — "tests" are exact commands with expected
envelope output.

**Tech Stack:** Dediren agent bundle (Java 21+), bash, python3 (no extra
packages), Claude Code Agent tool for subagent dispatch.

## Global Constraints

- Baseline: product code at commit `71676a6` (v2026.06.10). Docs-only commits
  after it are acceptable; any non-docs delta aborts the review.
- Review workspace (all generated artifacts, never committed):
  `WS=/tmp/claude/dediren-review-2026-07-03`. Every bash block re-declares
  `WS` and `BUNDLE` because shell state does not persist between tool calls.
  This path is the spec's "scratchpad": a stable, sandbox-writable location
  is used instead of the per-session scratchpad so the review can resume
  across sessions.
- `BUNDLE="$WS/dediren-agent-bundle-2026.06.10"` — the freshly built bundle.
- Severity vocabulary: `block` / `warn` / `info` (matches repo audit gates).
- Environment quirks: `./mvnw` and `git commit` (SSH signing) need the Bash
  sandbox disabled; everything else runs sandboxed. Subagents have no network
  access — network-caused failures are environment notes, not product
  findings.
- Reviewer role isolation is enforced by prompt contract and checked by the
  verification pass; subagent shells may start in the repo cwd, so every
  brief orders an immediate `cd` to its own workspace.
- Findings interchange format (every reviewer returns exactly this JSON, no
  markdown fences):

```json
{
  "viewpoint": "<viewpoint-id>",
  "scenario_completed": true,
  "steps": [
    {"name": "...", "command": "...", "status": "ok|error",
     "decidable_from_stdout": true, "notes": "..."}
  ],
  "measurements": [
    {"metric": "...", "value": 0, "unit": "ms|bytes|count",
     "command": "exact command that produced it"}
  ],
  "findings": [
    {"id": "XX-1", "severity": "block|warn|info",
     "type": "qualitative|measurement",
     "title": "...", "summary": "...", "evidence": "...",
     "repro": ["exact command 1", "exact command 2"]}
  ],
  "environment_notes": ["..."]
}
```

  Finding id prefixes: `SEED-` (groundwork), `CS-` (cold-start), `PA-`
  (plugin author), `MT-` (maintainer), `PF-` (performance). `steps` is
  required for cold-start, optional otherwise. `measurements` is required for
  performance, optional otherwise.
- Qualitative findings without a working `repro` are downgraded to `info` at
  synthesis; verifiers judge only what they can attempt.

---

### Task 1: Groundwork A — baseline gate and clean bundle build

**Files:**
- Create: `/tmp/claude/dediren-review-2026-07-03/` (workspace)
- Create: `/tmp/claude/dediren-review-2026-07-03/findings/seed.json`
- Regenerate: `dist/` (git-ignored build output; wiped first — see Step 3)

**Interfaces:**
- Consumes: repo `main`, Maven Wrapper.
- Produces: `$BUNDLE` (unpacked fresh bundle), `$WS/findings/seed.json`
  containing seed finding `SEED-1`. Later tasks rely on `$BUNDLE/bin/dediren`
  and `$BUNDLE/docs/agent-usage.md` existing.

- [ ] **Step 1: Verify the baseline**

```bash
cd /home/souroldgeezer/repos/dediren
git status --short --branch
git log --oneline 71676a6..HEAD -- ':!docs'
```

Expected: status shows only untracked user files (no modified tracked files);
the `git log` output is EMPTY (docs-only commits since baseline). If any
non-docs commit appears, STOP and ask the user whether to re-baseline.

- [ ] **Step 2: Sanity gate — full test suite** (sandbox disabled)

```bash
cd /home/souroldgeezer/repos/dediren && ./mvnw test
```

Expected: `BUILD SUCCESS`. On failure, STOP: the review must not run on a
broken checkout.

- [ ] **Step 3: Wipe stale dist and rebuild the bundle** (sandbox disabled)

The checked-out `dist/` accumulates jars across releases — the existing
tarball ships 11 stale `2026.06.9` jars next to the `2026.06.10` ones
(verified 2026-07-03 with `tar -tzf dist/dediren-agent-bundle-2026.06.10.tar.gz | grep -c '2026\.06\.9'` → `11`).
`dist/` is git-ignored generated output; wiping it is safe and required for a
clean review artifact.

```bash
cd /home/souroldgeezer/repos/dediren
rm -rf dist/
./mvnw -pl dist-tool -am verify -Pdist-build
```

Expected: `BUILD SUCCESS` and a fresh
`dist/dediren-agent-bundle-2026.06.10.tar.gz`.

- [ ] **Step 4: Verify the fresh tarball is clean, then record SEED-1**

```bash
cd /home/souroldgeezer/repos/dediren
tar -tzf dist/dediren-agent-bundle-2026.06.10.tar.gz | grep -c '2026\.06\.9' || true
```

Expected: `0`. If non-zero, the accumulation is inside the packaging step
itself, not just a dirty directory — upgrade SEED-1 severity to `block` in
the next step.

```bash
WS=/tmp/claude/dediren-review-2026-07-03
mkdir -p "$WS/findings"
cat > "$WS/findings/seed.json" <<'EOF'
{
  "viewpoint": "groundwork",
  "scenario_completed": true,
  "findings": [
    {"id": "SEED-1", "severity": "warn", "type": "qualitative",
     "title": "dist packaging accumulates stale prior-version jars",
     "summary": "Building the bundle over an existing dist/ directory leaves previous-version jars (11 x 2026.06.9) inside the shipped tar.gz alongside current jars. The published 2026.06.10 archive shipped this way. Risk: stale classes on the launcher classpath and inflated archives.",
     "evidence": "tar -tzf dist/dediren-agent-bundle-2026.06.10.tar.gz | grep -c '2026\\.06\\.9' returned 11 on the pre-review checkout (2026-07-03); returned 0 after rm -rf dist/ and a fresh -Pdist-build.",
     "repro": ["cd /home/souroldgeezer/repos/dediren", "git stash --include-untracked || true", "./mvnw -pl dist-tool -am verify -Pdist-build", "./mvnw versions:set -DnewVersion=9999.99.0 -DprocessAllModules=true -DgenerateBackupPoms=false && ./mvnw -pl dist-tool -am verify -Pdist-build && tar -tzf dist/dediren-agent-bundle-9999.99.0.tar.gz | grep -c '2026\\.06\\.10' ; git checkout -- . "]}
  ],
  "environment_notes": []
}
EOF
python3 -c "import json; json.load(open('$WS/findings/seed.json')); print('seed.json valid')"
```

Expected: `seed.json valid`. (The repro simulates a version bump to show
old-version jars surviving into the next archive; verifiers may substitute a
lighter reproduction — building twice without cleaning — if the version-set
step is too invasive. It must restore the worktree afterwards.)

- [ ] **Step 5: Unpack the bundle into the workspace and probe it**

```bash
WS=/tmp/claude/dediren-review-2026-07-03
mkdir -p "$WS"
tar -xzf /home/souroldgeezer/repos/dediren/dist/dediren-agent-bundle-2026.06.10.tar.gz -C "$WS"
BUNDLE="$WS/dediren-agent-bundle-2026.06.10"
"$BUNDLE/bin/dediren" --version
ls "$BUNDLE/docs/agent-usage.md" "$BUNDLE/schemas/model.schema.json" "$BUNDLE/plugins"
```

Expected: version output contains `2026.06.10`; both listed files exist;
`plugins/` lists five `*.manifest.json` files.

---

### Task 2: Groundwork B — scaled models, scenario brief, schema cache, warm-up

**Files:**
- Create: `$WS/generate_models.py`, `$WS/models/model-{10,100,1000}.json`
- Create: `$WS/scenario-brief.md`
- Create: `$WS/schema-cache/` (populated), `$WS/warmup/` (throwaway)

**Interfaces:**
- Consumes: `$BUNDLE` from Task 1.
- Produces: `$WS/models/model-10.json`, `model-100.json`, `model-1000.json`
  (valid archimate-profile source models); `$WS/scenario-brief.md` (input for
  cold-start and performance briefs); `$WS/schema-cache` (offline OEF/XMI
  schema cache for all reviewers).

- [ ] **Step 1: Write the model generator**

Write exactly this file (verified against the 2026.06.10 CLI on 2026-07-03:
all three outputs validate `ok` with zero diagnostics):

```bash
WS=/tmp/claude/dediren-review-2026-07-03
mkdir -p "$WS/models"
cat > "$WS/generate_models.py" <<'EOF'
#!/usr/bin/env python3
"""Generate scaled, valid Dediren ArchiMate source models."""
import json, random, sys

VERSION = "2026.06.10"

def build(n_nodes, seed=42):
    rnd = random.Random(seed)
    counts = {
        "BusinessActor": max(1, round(n_nodes * 0.10)),
        "ApplicationService": max(1, round(n_nodes * 0.20)),
        "ApplicationComponent": max(1, round(n_nodes * 0.35)),
        "DataObject": max(1, round(n_nodes * 0.15)),
        "TechnologyService": max(1, round(n_nodes * 0.10)),
        "Node": max(1, round(n_nodes * 0.10)),
    }
    counts["ApplicationComponent"] += n_nodes - sum(counts.values())

    nodes, by_type = [], {}
    for t, c in counts.items():
        for i in range(c):
            nid = f"{t.lower()}-{i}"
            nodes.append({"id": nid, "type": t, "label": f"{t} {i}", "properties": {}})
            by_type.setdefault(t, []).append(nid)

    rels = []
    def rel(rtype, src, dst):
        rels.append({"id": f"r{len(rels)}-{rtype.lower()}", "type": rtype,
                     "source": src, "target": dst, "label": rtype.lower(),
                     "properties": {}})

    comps = by_type["ApplicationComponent"]
    for i, svc in enumerate(by_type["ApplicationService"]):
        rel("Realization", comps[i % len(comps)], svc)
        rel("Serving", svc, rnd.choice(by_type["BusinessActor"]))
    for dob in by_type["DataObject"]:
        rel("Access", rnd.choice(comps), dob)
    for i, tsvc in enumerate(by_type["TechnologyService"]):
        rel("Realization", by_type["Node"][i % len(by_type["Node"])], tsvc)
        rel("Serving", tsvc, rnd.choice(comps))
    for i in range(len(comps) - 1):
        if rnd.random() < 0.6:
            rel("Flow", comps[i], comps[i + 1])

    return {
        "model_schema_version": "model.schema.v1",
        "required_plugins": [{"id": "generic-graph", "version": VERSION}],
        "nodes": nodes,
        "relationships": rels,
        "plugins": {"generic-graph": {
            "semantic_profile": "archimate",
            "views": [{"id": "main", "label": f"Scale {n_nodes}",
                       "nodes": [n["id"] for n in nodes],
                       "relationships": [r["id"] for r in rels],
                       "groups": []}],
        }},
    }

if __name__ == "__main__":
    outdir = sys.argv[1]
    for n in (10, 100, 1000):
        m = build(n)
        path = f"{outdir}/model-{n}.json"
        with open(path, "w") as f:
            json.dump(m, f, indent=1)
        print(path, len(m["nodes"]), "nodes", len(m["relationships"]), "relationships")
EOF
python3 "$WS/generate_models.py" "$WS/models"
```

Expected output (exact node counts; relationship counts 9/94/953):

```
/tmp/claude/dediren-review-2026-07-03/models/model-10.json 10 nodes 9 relationships
/tmp/claude/dediren-review-2026-07-03/models/model-100.json 100 nodes 94 relationships
/tmp/claude/dediren-review-2026-07-03/models/model-1000.json 1000 nodes 953 relationships
```

- [ ] **Step 2: Validate all three models with the real CLI**

```bash
WS=/tmp/claude/dediren-review-2026-07-03
BUNDLE="$WS/dediren-agent-bundle-2026.06.10"
for n in 10 100 1000; do
  echo -n "model-$n: "
  "$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate \
    --input "$WS/models/model-$n.json" 2>/dev/null \
    | python3 -c "import json,sys; e=json.load(sys.stdin); print(e['status'], [d.get('code') for d in e.get('diagnostics',[])])"
done
```

Expected: three lines, each `ok []`. Any other status: fix the generator
before proceeding (do not hand broken fixtures to reviewers).

- [ ] **Step 3: Write the scenario brief**

```bash
WS=/tmp/claude/dediren-review-2026-07-03
cat > "$WS/scenario-brief.md" <<'EOF'
# TicketFlow Modeling Brief

Model "TicketFlow", a small helpdesk product, as an ArchiMate model and
produce render and export evidence.

Facts to model:

- Customers submit support tickets through a web Portal.
- The Portal calls the Ticket API.
- The Ticket API reads and writes ticket records.
- A Notification Worker reads ticket records and notifies Support Agents.
- Support Agents handle tickets in an Agent Console.
- Everything server-side runs on a shared Application Runtime.

Deliverables, in order; decide success of each step from stdout JSON only:

1. A validated source model (archimate profile).
2. A layout request projected for one view of your choice.
3. A computed layout that passes validate-layout.
4. An SVG file rendered with the bundle's default SVG render policy.
5. An OEF export using the bundle's default OEF export policy.
EOF
wc -l "$WS/scenario-brief.md"
```

Expected: about 22 lines written.

- [ ] **Step 4: Populate the offline schema cache** (sandbox disabled — this
  is the single network-requiring step; it uses the product's own cache
  mechanism so reviewers can export offline)

```bash
WS=/tmp/claude/dediren-review-2026-07-03
BUNDLE="$WS/dediren-agent-bundle-2026.06.10"
mkdir -p "$WS/schema-cache"
export DEDIREN_SCHEMA_CACHE_DIR="$WS/schema-cache"
"$BUNDLE/bin/dediren" export --plugin archimate-oef \
  --policy "$BUNDLE/fixtures/export-policy/default-oef.json" \
  --source "$BUNDLE/fixtures/source/valid-archimate-oef.json" \
  --layout "$BUNDLE/fixtures/layout-result/archimate-oef-basic.json" 2>/dev/null \
  | python3 -c "import json,sys; print('oef:', json.load(sys.stdin)['status'])"
"$BUNDLE/bin/dediren" export --plugin uml-xmi \
  --policy "$BUNDLE/fixtures/export-policy/default-uml-xmi.json" \
  --source "$BUNDLE/fixtures/source/valid-uml-basic.json" \
  --layout "$BUNDLE/fixtures/layout-result/uml-basic.json" 2>/dev/null \
  | python3 -c "import json,sys; print('xmi:', json.load(sys.stdin)['status'])"
ls "$WS/schema-cache"
```

Expected: `oef: ok`, `xmi: ok`, and the cache directory is non-empty.
(Without a cache dir the export fails with `DEDIREN_OEF_SCHEMA_UNAVAILABLE`
— verified 2026-07-03.)

- [ ] **Step 5: CDS warm-up** (so first-run archive creation does not skew
  reviewer timings; the performance brief provisions its own cold copy)

```bash
WS=/tmp/claude/dediren-review-2026-07-03
BUNDLE="$WS/dediren-agent-bundle-2026.06.10"
mkdir -p "$WS/warmup" && cd "$WS/warmup"
"$BUNDLE/bin/dediren" validate --plugin generic-graph --profile archimate --input "$WS/models/model-10.json" > /dev/null 2>&1
"$BUNDLE/bin/dediren" project --target layout-request --plugin generic-graph --view main --input "$WS/models/model-10.json" > lr.json 2>/dev/null
"$BUNDLE/bin/dediren" layout --plugin elk-layout --input lr.json > lres.json 2>/dev/null
"$BUNDLE/bin/dediren" validate-layout --input lres.json > /dev/null 2>&1
"$BUNDLE/bin/dediren" render --plugin render --policy "$BUNDLE/fixtures/render-policy/default-svg.json" --input lres.json > rr.json 2>/dev/null
python3 -c "import json; print('render:', json.load(open('rr.json'))['status'])"
ls "$BUNDLE/cds" | head
```

Expected: `render: ok` and at least one `.jsa` file in `$BUNDLE/cds`.

---

### Task 3: Cold-start AI agent reviewer

**Files:**
- Create: `$WS/findings/cold-start.json`
- Create: `$WS/cold-start/` (reviewer working directory)

**Interfaces:**
- Consumes: `$BUNDLE`, `$WS/scenario-brief.md`, `$WS/schema-cache`.
- Produces: `$WS/findings/cold-start.json` in the Global Constraints findings
  format, `viewpoint: "cold-start-agent"`, ids `CS-n`.

Tasks 3–6 are independent: dispatch all four reviewer agents in a single
parallel batch, then validate each returned payload as it arrives.

- [ ] **Step 1: Create the reviewer working directory**

```bash
mkdir -p /tmp/claude/dediren-review-2026-07-03/cold-start
```

- [ ] **Step 2: Dispatch the cold-start reviewer** (Agent tool,
  `subagent_type: general-purpose`) with exactly this prompt:

```text
You are a fresh AI coding agent encountering the "Dediren" product for the
first time. You have NO prior knowledge of this product or its source code,
and you must behave that way.

HARD RULES:
- You may read and write ONLY under these locations:
  - Product bundle (read-only): /tmp/claude/dediren-review-2026-07-03/dediren-agent-bundle-2026.06.10
  - Task brief (read-only): /tmp/claude/dediren-review-2026-07-03/scenario-brief.md
  - Your working directory (read-write): /tmp/claude/dediren-review-2026-07-03/cold-start
- You must NOT read anything under /home/souroldgeezer/repos/dediren. That is
  the vendor's source repository and it does not exist for you. Your shell may
  start there: immediately cd to your working directory and use absolute
  paths everywhere.
- You have no network access. A pre-populated schema cache exists at
  /tmp/claude/dediren-review-2026-07-03/schema-cache; the product's own
  documentation explains the environment variable that consumes such a cache.
  If a command fails purely because of blocked network and you cannot resolve
  it with documented offline mechanisms, record it under environment_notes,
  not findings.
- Java 21+ is already on PATH.

YOUR TASK:
1. Read the bundle's docs/agent-usage.md.
2. Complete every deliverable in the task brief end-to-end, writing all
   artifacts into your working directory.
3. Decide success or failure of every command from its stdout JSON envelope
   only. Never parse stderr.

While working, keep a precise friction log: every time you stall, guess,
re-read documentation, misinterpret an envelope, hit a failing command, or
need information the documentation does not provide. Recovered friction still
counts.

RETURN FORMAT — your final message must be ONLY this JSON object (no fences,
no prose around it):
{
  "viewpoint": "cold-start-agent",
  "scenario_completed": <bool>,
  "steps": [
    {"name": "<deliverable or sub-step>", "command": "<exact command>",
     "status": "ok|error", "decidable_from_stdout": <bool>,
     "notes": "<short>"}
  ],
  "findings": [
    {"id": "CS-1", "severity": "block|warn|info",
     "type": "qualitative",
     "title": "<one line>",
     "summary": "<what happened and why it matters to a first-contact agent>",
     "evidence": "<what you observed, quoted where useful>",
     "repro": ["<exact command another agent can run>", "..."]}
  ],
  "environment_notes": ["<sandbox/network artifacts, if any>"]
}

Severity guide: block = a first-contact agent cannot complete the scenario
without outside help; warn = completes but with real friction or misleading
output; info = worth knowing, low impact. Number findings CS-1, CS-2, ...
Every finding MUST include runnable repro commands.
```

- [ ] **Step 3: Save and validate the returned payload**

Save the agent's final message verbatim to
`$WS/findings/cold-start.json`, then:

```bash
WS=/tmp/claude/dediren-review-2026-07-03
python3 - <<'EOF'
import json
d = json.load(open("/tmp/claude/dediren-review-2026-07-03/findings/cold-start.json"))
assert d["viewpoint"] == "cold-start-agent"
assert isinstance(d["scenario_completed"], bool) and "steps" in d
for f in d["findings"]:
    assert f["id"].startswith("CS-") and f["severity"] in ("block","warn","info")
    assert f["type"] == "qualitative" and f["repro"], f["id"]
print("cold-start.json valid:", len(d["findings"]), "findings, completed:", d["scenario_completed"])
EOF
```

Expected: `cold-start.json valid: ...`. If the payload is not valid JSON,
send the agent one follow-up asking for the JSON only; if it still fails,
salvage findings manually into the same shape and note the deviation in the
report's environment caveats.

---

### Task 4: Community plugin author reviewer

**Files:**
- Create: `$WS/findings/plugin-author.json`
- Create: `$WS/plugin-author/` (reviewer project directory)

**Interfaces:**
- Consumes: `$BUNDLE`, repo `README.md` (public docs only).
- Produces: `$WS/findings/plugin-author.json`, `viewpoint:
  "community-plugin-author"`, ids `PA-n`; toy plugin under
  `$WS/plugin-author/` (never committed).

- [ ] **Step 1: Create the reviewer project directory**

```bash
mkdir -p /tmp/claude/dediren-review-2026-07-03/plugin-author/project
```

- [ ] **Step 2: Dispatch the plugin-author reviewer** (Agent tool,
  `subagent_type: general-purpose`) with exactly this prompt:

```text
You are an experienced developer outside the Dediren project who wants to
ship a third-party plugin for it. You know nothing about Dediren internals
and have no access to its source code.

HARD RULES:
- You may read ONLY:
  - The product bundle: /tmp/claude/dediren-review-2026-07-03/dediren-agent-bundle-2026.06.10
    (its docs/, schemas/, plugins/*.manifest.json and fixtures/ are the
    published contract surface)
  - The public README: /home/souroldgeezer/repos/dediren/README.md
- You must NOT read any other path under /home/souroldgeezer/repos/dediren —
  no core/, cli/, contracts/, plugins/ source, no docs/ other than README.md.
  If you cannot learn something from the bundle or README, that inability IS
  a finding.
- Read-write workspace: /tmp/claude/dediren-review-2026-07-03/plugin-author
  Your shell may start in the vendor repo: immediately cd to your workspace.
- No network access. Java 21+ and python3 are on PATH.

YOUR TASK: build "ticket-stats", a minimal third-party export-style plugin
that reads whatever the Dediren CLI sends a plugin and outputs a JSON
envelope whose data contains node and relationship counts. Concretely:
1. From the published contract surface alone, work out the plugin protocol:
   manifest shape, executable contract, capability negotiation, stdin/stdout
   envelopes, and how a project registers third-party plugins.
2. Implement the plugin as a self-contained python3 executable plus manifest
   inside your workspace project directory
   (/tmp/claude/dediren-review-2026-07-03/plugin-author/project).
3. Get the real CLI
   (/tmp/claude/dediren-review-2026-07-03/dediren-agent-bundle-2026.06.10/bin/dediren)
   to discover and execute your plugin against a bundle fixture input, using
   only documented registration mechanisms (project plugin directory or
   documented environment variables).
4. Keep a friction log the whole way: everything you had to guess, every
   schema that was ambiguous, every error message that helped or misled you,
   every dead end.

Success is ideal but not required — a precise account of where the published
contract stops carrying you is exactly as valuable.

RETURN FORMAT — your final message must be ONLY this JSON object (no fences,
no prose around it):
{
  "viewpoint": "community-plugin-author",
  "scenario_completed": <bool: plugin discovered AND executed by the CLI>,
  "findings": [
    {"id": "PA-1", "severity": "block|warn|info",
     "type": "qualitative",
     "title": "<one line>",
     "summary": "<what was missing/guessed/misleading and its impact on a
       third-party author>",
     "evidence": "<what you observed>",
     "repro": ["<exact commands>"]}
  ],
  "environment_notes": ["..."]
}

Severity guide: block = a competent outside author cannot complete a working
plugin from the published surface; warn = completed only by guessing or
trial-and-error a doc should have covered; info = polish. Number findings
PA-1, PA-2, ... Every finding MUST include runnable repro commands.
```

- [ ] **Step 3: Save and validate the returned payload**

Save the final message to `$WS/findings/plugin-author.json`, then:

```bash
python3 - <<'EOF'
import json
d = json.load(open("/tmp/claude/dediren-review-2026-07-03/findings/plugin-author.json"))
assert d["viewpoint"] == "community-plugin-author"
for f in d["findings"]:
    assert f["id"].startswith("PA-") and f["severity"] in ("block","warn","info") and f["repro"]
print("plugin-author.json valid:", len(d["findings"]), "findings, completed:", d["scenario_completed"])
EOF
```

Expected: `plugin-author.json valid: ...` (same salvage rule as Task 3).

---

### Task 5: Maintainer-in-2027 reviewer

**Files:**
- Create: `$WS/findings/maintainer.json`

**Interfaces:**
- Consumes: full repo read access (perspective isolation, not information).
- Produces: `$WS/findings/maintainer.json`, `viewpoint: "maintainer-2027"`,
  ids `MT-n`.

- [ ] **Step 1: Dispatch the maintainer reviewer** (Agent tool,
  `subagent_type: general-purpose`) with exactly this prompt:

```text
You are the sole maintainer of the Dediren repository at
/home/souroldgeezer/repos/dediren, but it is mid-2027: the original author is
unavailable, eighteen months of ecosystem drift have accumulated, and you
must assess what this codebase will cost you to evolve. You have full
read access to the repository, its git history, and its docs. Do NOT modify
anything; do NOT run ./mvnw (builds and tests are not your job — evidence
comes from reading code, poms, schemas, and git history). Write scratch
notes only under /tmp/claude/dediren-review-2026-07-03/maintainer (create
it).

Run these five evidence-backed analyses:

1. SCHEMA V2 CHANGE SURFACE. Suppose model.schema.v1 must become
   model.schema.v2 with one breaking change (e.g. relationships gain a
   required "kind" field). Walk the ACTUAL change surface: grep for
   model.schema.v1 and schema-version constants across schemas/, contracts/,
   core/, cli/, plugins/, fixtures/, docs/. Count files, name them, and
   assess whether the migration path (dual-version support? big-bang?) is
   designed or undefined.

2. DEPENDENCY UPGRADE EXPOSURE. From the root pom.xml and module poms:
   list pinned versions of Eclipse ELK, Jackson (or whatever JSON lib is
   used), JUnit, and the Java release target. For each, judge blast radius
   of the next major upgrade from how widely its types appear in
   non-test source (grep counts per module).

3. COUPLING: DESIGNED OR DEBT. CLAUDE.md's "Files That Move Together" lists
   6+ file groups per public-shape change. Test it against reality: pick the
   two most recent commits that touched schemas/ (git log --oneline --
   schemas/), measure their real fan-out (git show --stat), and compare with
   the documented lists. Judge: is this cohesion an intentional contract
   discipline with enforcement (tests that fail when surfaces drift), or
   tribal knowledge that will silently rot?

4. BUS FACTOR. Identify knowledge that exists ONLY in docs/superpowers/
   plans and specs (retired decisions, rationale, constraints) that a new
   maintainer would need but that neither code, tests, nor
   docs/architecture-guidelines.md carry. Name the specific gaps.

5. IN-FLIGHT TRANSPORT SPEC LOAD. Read
   docs/superpowers/specs/2026-07-01-inprocess-first-party-plugin-transport-design.md.
   Assess the maintenance load it would add (dual transports, ArchUnit-
   enforced boundary, per-plugin adapters) against the maintenance it
   removes. As the 2027 maintainer, would you rather own it or not?

RETURN FORMAT — your final message must be ONLY this JSON object (no fences,
no prose around it):
{
  "viewpoint": "maintainer-2027",
  "scenario_completed": true,
  "findings": [
    {"id": "MT-1", "severity": "block|warn|info",
     "type": "qualitative",
     "title": "<one line>",
     "summary": "<the change-cost or evolution risk and why it matters>",
     "evidence": "<file paths, line refs, git shas, grep counts>",
     "repro": ["<exact read-only commands (grep/git) that reproduce the evidence>"]}
  ],
  "environment_notes": ["..."]
}

Severity guide: block = evolution path is undefined or would require
archaeology to execute safely; warn = doable but costlier than it should be;
info = observation. Number findings MT-1, MT-2, ... Positive capabilities
worth preserving may be reported as info findings. Every finding MUST include
runnable read-only repro commands.
```

- [ ] **Step 2: Save and validate the returned payload**

Save the final message to `$WS/findings/maintainer.json`, then:

```bash
python3 - <<'EOF'
import json
d = json.load(open("/tmp/claude/dediren-review-2026-07-03/findings/maintainer.json"))
assert d["viewpoint"] == "maintainer-2027"
for f in d["findings"]:
    assert f["id"].startswith("MT-") and f["severity"] in ("block","warn","info") and f["repro"]
print("maintainer.json valid:", len(d["findings"]), "findings")
EOF
```

Expected: `maintainer.json valid: ...` (same salvage rule as Task 3).

---

### Task 6: Performance-conscious developer reviewer

**Files:**
- Create: `$WS/findings/performance.json`
- Create: `$WS/perf/` (measurement workspace)

**Interfaces:**
- Consumes: `$BUNDLE`, `$WS/models/*`, `$WS/schema-cache`, repo (dev-loop
  timings), `dist/dediren-agent-bundle-2026.06.10.tar.gz` (cold-copy source).
- Produces: `$WS/findings/performance.json`, `viewpoint:
  "performance-developer"`, ids `PF-n`, with a REQUIRED `measurements` array
  — this is the baseline the in-process transport spec will cite.

- [ ] **Step 1: Create the measurement workspace**

```bash
mkdir -p /tmp/claude/dediren-review-2026-07-03/perf
```

- [ ] **Step 2: Dispatch the performance reviewer** (Agent tool,
  `subagent_type: general-purpose`) with exactly this prompt:

```text
You are a performance-conscious developer evaluating the Dediren agent
bundle. Your product is NUMBERS: every claim must carry the exact command
that produced it, so anyone can re-run it. Judgments go in findings;
raw numbers go in measurements.

Environment:
- Bundle (warm, CDS archives already created):
  /tmp/claude/dediren-review-2026-07-03/dediren-agent-bundle-2026.06.10
- Scaled models: /tmp/claude/dediren-review-2026-07-03/models/model-{10,100,1000}.json
  (single view id "main"; archimate profile)
- Offline schema cache (export needs it):
  export DEDIREN_SCHEMA_CACHE_DIR=/tmp/claude/dediren-review-2026-07-03/schema-cache
- Fresh tarball for cold-start tests:
  /home/souroldgeezer/repos/dediren/dist/dediren-agent-bundle-2026.06.10.tar.gz
- Workspace (read-write): /tmp/claude/dediren-review-2026-07-03/perf
- No network. Use python3 for timing (time.monotonic around subprocess.run)
  or bash `time`; report MEDIAN of 5 runs for anything under 10 s, single
  runs above that. For peak memory use /usr/bin/time -v (record
  "Maximum resident set size"); if unavailable, note it in
  environment_notes and skip RSS.
- The two Maven timings in measure set D are the ONLY commands you run with
  the Bash sandbox disabled (they need writable /tmp and ~/.m2); everything
  else stays sandboxed.

MEASURE SET A — pipeline overhead (model-100):
For each stage below, median-of-5 wall ms, plus record output size in bytes
(wc -c) of each stage's stdout artifact:
  1. validate:        bin/dediren validate --plugin generic-graph --profile archimate --input model-100.json
  2. project:         bin/dediren project --target layout-request --plugin generic-graph --view main --input model-100.json
  3. layout:          bin/dediren layout --plugin elk-layout --input <layout-request>
  4. validate-layout: bin/dediren validate-layout --input <layout-result>
  5. render:          bin/dediren render --plugin render --policy fixtures/render-policy/default-svg.json --input <layout-result>
  6. export:          bin/dediren export --plugin archimate-oef --policy fixtures/export-policy/default-oef.json --source model-100.json --layout <layout-result>
Also measure the JVM floor: median-of-5 of `bin/dediren --version`, and one
plugin capability probe, e.g. `bin/dediren-plugin-generic-graph capabilities`
fed by: echo. Then repeat stages 1-3 with
DEDIREN_TRUST_MANIFEST_CAPABILITIES=1 and report the delta — that flag
removes one JVM start per plugin operation.

MEASURE SET B — scale ceiling: run stages 1-5 on model-10, model-100,
model-1000. Per stage per scale: wall ms, peak RSS of the CLI process tree
(/usr/bin/time -v on the CLI invocation), stdout artifact bytes, and for
render extract the SVG and record its byte size. Identify which stage
degrades first and whether growth looks linear or worse (state the ratio
1000 vs 100).

MEASURE SET C — cold start and footprint:
  - tarball bytes; unpacked bytes (du -sb).
  - Extract a PRISTINE copy under your workspace, point DEDIREN_CDS_DIR at a
    fresh empty dir, and measure first-call vs fifth-call wall time of
    `bin/dediren validate ... model-10.json` — that is the real cold-start
    penalty including CDS archive creation.

MEASURE SET D — developer loop (sandbox disabled, single run each,
run from /home/souroldgeezer/repos/dediren):
  - ./mvnw test
  - ./mvnw -Pquality verify
Record wall seconds for each.

RETURN FORMAT — your final message must be ONLY this JSON object (no fences,
no prose around it):
{
  "viewpoint": "performance-developer",
  "scenario_completed": true,
  "measurements": [
    {"metric": "<set>.<stage-or-name>[.<scale>]", "value": <number>,
     "unit": "ms|s|bytes", "command": "<exact command>"}
  ],
  "findings": [
    {"id": "PF-1", "severity": "block|warn|info",
     "type": "measurement",
     "title": "<one line>",
     "summary": "<the judgment the numbers support>",
     "evidence": "<the specific measurements backing it>",
     "repro": ["<exact commands>"]}
  ],
  "environment_notes": ["..."]
}

Severity guide: block = unusable at a realistic scale or cost; warn = a real
tax users will feel (e.g. spawn overhead dominating small-model pipelines);
info = fine but worth recording. Number findings PF-1, PF-2, ...
```

- [ ] **Step 3: Save and validate the returned payload**

Save the final message to `$WS/findings/performance.json`, then:

```bash
python3 - <<'EOF'
import json
d = json.load(open("/tmp/claude/dediren-review-2026-07-03/findings/performance.json"))
assert d["viewpoint"] == "performance-developer"
assert d["measurements"], "measurements array must be non-empty"
for m in d["measurements"]:
    assert m["command"] and isinstance(m["value"], (int, float))
for f in d["findings"]:
    assert f["id"].startswith("PF-") and f["severity"] in ("block","warn","info")
print("performance.json valid:", len(d["measurements"]), "measurements,", len(d["findings"]), "findings")
EOF
```

Expected: `performance.json valid: ...` (same salvage rule as Task 3).

---

### Task 7: Verification pass

**Files:**
- Create: `$WS/findings/verdicts.json`

**Interfaces:**
- Consumes: all five findings files (`seed`, `cold-start`, `plugin-author`,
  `maintainer`, `performance`).
- Produces: `$WS/findings/verdicts.json` — array of
  `{"finding_id", "verdict": "confirmed|plausible|refuted", "notes"}` for
  every `type: "qualitative"` finding. `type: "measurement"` findings skip
  verification per the spec.

- [ ] **Step 1: Enumerate the findings to verify**

```bash
python3 - <<'EOF'
import json, glob
qual = []
for p in sorted(glob.glob("/tmp/claude/dediren-review-2026-07-03/findings/*.json")):
    if p.endswith(("verdicts.json", "to-verify.json")):
        continue
    d = json.load(open(p))
    for f in d.get("findings", []):
        if f["type"] == "qualitative":
            qual.append({"finding_id": f["id"], "title": f["title"],
                         "summary": f["summary"], "evidence": f["evidence"],
                         "repro": f["repro"]})
json.dump(qual, open("/tmp/claude/dediren-review-2026-07-03/findings/to-verify.json", "w"), indent=1)
print(len(qual), "qualitative findings to verify:", [q["finding_id"] for q in qual])
EOF
```

Expected: a count and id list; `to-verify.json` written.

- [ ] **Step 2: Dispatch one verifier agent per finding** (Agent tool,
  `subagent_type: general-purpose`; batch them in parallel, up to ~6 at a
  time). For each entry in `to-verify.json`, substitute it into this prompt
  where `<FINDING_JSON>` appears:

```text
You are an independent verifier. You receive one claimed finding about the
Dediren product. Your ONLY job is to attempt reproduction from its "repro"
commands and evidence — you are deliberately blind to the reviewer's
reasoning and must not extend or improve the finding.

Environment facts:
- Product bundle: /tmp/claude/dediren-review-2026-07-03/dediren-agent-bundle-2026.06.10
- Models: /tmp/claude/dediren-review-2026-07-03/models/
- Offline schema cache: export DEDIREN_SCHEMA_CACHE_DIR=/tmp/claude/dediren-review-2026-07-03/schema-cache
- Vendor repo (read-only, only if the repro commands reference it):
  /home/souroldgeezer/repos/dediren — do not modify it; if a repro would
  modify it, verify with a non-mutating variant or the closest safe subset,
  and say so in notes.
- Scratch space: /tmp/claude/dediren-review-2026-07-03/verify (create your
  own subdirectory). No network access.

THE FINDING:
<FINDING_JSON>

Procedure: run the repro commands (adapted only as needed for your scratch
paths), observe outcomes, and compare against the claim.

Verdict rules:
- "confirmed": the claimed behavior reproduced.
- "plausible": could not fully reproduce (missing precondition, environment
  difference, prohibitively invasive repro) but observed nothing
  contradicting the claim.
- "refuted": the repro contradicts the claim, or the claimed behavior is
  actually caused by the reviewer's own error or the sandbox environment
  rather than the product.

RETURN FORMAT — your final message must be ONLY this JSON object:
{"finding_id": "<id>", "verdict": "confirmed|plausible|refuted",
 "notes": "<what you ran and what you saw, 1-3 sentences>"}
```

- [ ] **Step 3: Merge verdicts and summarize**

Collect every verifier's JSON into a single array and save it as
`$WS/findings/verdicts.json`, then:

```bash
python3 - <<'EOF'
import json
verdicts = json.load(open("/tmp/claude/dediren-review-2026-07-03/findings/verdicts.json"))
todo = {q["finding_id"] for q in json.load(open("/tmp/claude/dediren-review-2026-07-03/findings/to-verify.json"))}
got = {v["finding_id"] for v in verdicts}
assert todo == got, f"missing verdicts: {todo - got}"
from collections import Counter
print(Counter(v["verdict"] for v in verdicts))
EOF
```

Expected: no assertion error; a `Counter({...})` line. Refuted findings are
dropped from the report body; their count appears in the synthesis section.

---

### Task 8: Consolidated report

**Files:**
- Create: `docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md`

**Interfaces:**
- Consumes: all findings files + `verdicts.json`.
- Produces: the committed report; Task 9 reads its synthesis section to
  choose plan clusters.

- [ ] **Step 1: Write the report**

Create `docs/superpowers/reviews/` and write the report with exactly this
skeleton, filled from the findings data (author real prose — the report is a
human-facing document, not a JSON dump):

```markdown
# Multi-Viewpoint Product Review — 2026-07-03

Baseline: `main` @ 71676a6 (v2026.06.10, docs-only commits after baseline).
Spec: docs/superpowers/specs/2026-07-03-multi-viewpoint-product-review-design.md

## Executive Summary

[3-6 sentences: overall verdict, the 2-4 findings that matter most, and the
single clearest strength worth preserving.]

## Findings Ranked by Product Impact

| Rank | ID | Severity | Verdict | Title | Viewpoint |
| ---- | -- | -------- | ------- | ----- | --------- |
[every confirmed/plausible finding, block first, then warn, then info]

## Viewpoint: Cold-Start AI Agent

Scenario completed: [yes/no]. [Narrative summary, then one subsection per
confirmed/plausible finding: id, severity, verdict, summary, evidence,
repro commands.]

## Viewpoint: Community Plugin Author

[same structure]

## Viewpoint: Maintainer in 2027

[same structure]

## Viewpoint: Performance-Conscious Developer

[judgment findings, same structure; numbers live in the appendix]

## Groundwork Seed Findings

[SEED-1 with verdict]

## Cross-Viewpoint Synthesis

[Clusters where viewpoints reinforce each other; the ranked story of what to
fix first and why; count of refuted findings dropped; follow-up plan files
drafted per cluster (paths).]

## Measured Baselines (Appendix)

[The full measurements table from the performance reviewer: metric, value,
unit, exact command. Note that these are the baseline numbers for the
in-process transport spec.]

## Review Artifacts & Environment Caveats

- Workspace: /tmp/claude/dediren-review-2026-07-03 (bundle, models, toy
  plugin, per-viewpoint findings JSON — not committed).
- Caveats: reviewer sandboxes had no network (offline schema cache was
  pre-provisioned and its location disclosed to reviewers — a small,
  unavoidable contamination of the cold-start premise); subagent role
  isolation is prompt-enforced.
```

- [ ] **Step 2: Verify and commit** (commit needs sandbox disabled)

```bash
cd /home/souroldgeezer/repos/dediren
git diff --check
git status --short --branch
git add docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md
git commit -m "docs(review): multi-viewpoint product review report"
```

Expected: `git diff --check` silent; only the report staged; commit
succeeds.

---

### Task 9: Follow-up plans

**Files:**
- Create: `docs/superpowers/plans/2026-07-03-<cluster-slug>.md` (one per
  cluster; slugs chosen at synthesis time from finding content)

**Interfaces:**
- Consumes: report synthesis clusters (confirmed/plausible `block` and `warn`
  findings only; `info` findings are report-only observations).
- Produces: committed, executable plan drafts.

- [ ] **Step 1: Cluster and draft one plan per cluster**

Group the confirmed/plausible block+warn findings by shared root cause (the
synthesis section already names the clusters). For each cluster write
`docs/superpowers/plans/2026-07-03-<cluster-slug>.md` with this structure:

```markdown
# <Cluster Title> Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** [one sentence]

**Source findings:** [ids + one-line titles, link to the review report]

**Architecture:** [2-3 sentences on the fix approach]

**Tech Stack:** [as applicable]

## Global Constraints

- Baseline findings and repro commands live in
  docs/superpowers/reviews/2026-07-03-multi-viewpoint-product-review.md.
- [repo-specific constraints that apply, e.g. Files That Move Together
  groups affected, verification lane from CLAUDE.md]

---

### Task 1: [first fix]

**Files:** [exact paths from the finding's evidence]

- [ ] Step 1: [reproduce the finding via its repro commands — expected: still fails]
- [ ] Step 2..n: [fix, verify repro now passes, run the CLAUDE.md
      verification lane for the touched area, commit]
```

Every plan must carry the finding's actual repro commands as its failing
"test" and the matching CLAUDE.md verification lane as its passing gate.
Do not draft plans for clusters the user may not want fixed silently — if a
cluster implies contract or behavior changes, mark its plan header with
`**Status:** draft — needs owner decision`.

- [ ] **Step 2: Verify and commit** (commit needs sandbox disabled)

```bash
cd /home/souroldgeezer/repos/dediren
git diff --check
git add docs/superpowers/plans/2026-07-03-<cluster-slug-1>.md docs/superpowers/plans/2026-07-03-<cluster-slug-2>.md
git commit -m "docs(review): follow-up plans from multi-viewpoint review"
git status --short --branch
```

Stage each new cluster plan by its explicit path — do NOT use a
`2026-07-03-*.md` glob, which also matches this plan file and would sweep in
any checkbox-progress edits made to it during execution. Expected: clean
check; only the new cluster plan files staged; final status shows no
unexpected staged changes.

- [ ] **Step 3: Handoff summary**

Report to the user: report path, plan paths with one-line rationale each,
verdict counts (confirmed/plausible/refuted), the measured-baseline headline
numbers, and the workspace path for artifacts.
