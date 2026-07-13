#!/usr/bin/env bash
# Re-baselines the checked-in render goldens (engines/render/src/test/resources/golden).
#
# Run this ONLY when a change deliberately moves rendered output, and READ THE DIFF: it is the
# geometry change every consumer's diagrams will see. RenderGoldenTest is the repo's only geometry
# oracle — the rest of the render suite asserts structure, not geometry — so a golden regenerated
# without reading it silently discards the protection.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q -pl engines/render -am test \
  -Dtest=RenderGoldenTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddediren.render.regenerate-goldens=true
echo "Goldens rewritten. Review with: git diff -- engines/render/src/test/resources/golden"
