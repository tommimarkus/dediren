#!/usr/bin/env bash
# Regenerates fixtures/layout-result/*.json from the real generic-graph + elk-layout engine
# pipeline (Plan B P2, Task 9). Opt-in and off by default: LayoutFixtureRegenerator
# (cli/src/test/java/dev/dediren/cli/LayoutFixtureRegenerator.java) only runs its regeneration
# body when -Ddediren.regen-layout-fixtures=true is set, mirroring the opt-in gating pattern used
# by engines/elk-layout's ElkLayoutRenderArtifacts test helper.
#
# Determinism precondition: this only reproduces byte-identical geometry when run with the
# repository's bundled Liberation Sans font metrics and the pinned Eclipse ELK version declared
# in the root pom.xml. Running against a system font (a different Java/fontconfig install without
# the bundled font) or a different ELK version WILL shift glyph-width-derived node sizes and
# route geometry — see memory "visual-defect-test-suite": the real-font oracle was non-hermetic
# under CI's logical Font.SANS_SERIF (16-18% wider) until Liberation Sans was bundled. Do not run
# this script, and do not trust its output, on a toolchain that has not been verified against
# that same bundled-font + pinned-ELK-version setup.
#
# Usage:
#   scripts/regen-layout-fixtures.sh
#
# The regenerated fixtures are NOT committed by this script. Inspect `git diff -- fixtures/layout-result/`
# before staging anything: the geometry is expected to change from any idealized/hand-authored
# numbers to real ELK output, and every changed fixture should gain real source_pointer values.
# Two of the fifteen fixtures under fixtures/layout-result/ are intentionally NOT covered by this
# regenerator (see LayoutFixtureRegenerator's class Javadoc for the full mapping investigation):
#   - uml-sequence-validatable.json: a hand-authored schema/quality oracle with no matching source
#     view.
#   - uml-sequence-fragment-chrome.json IS covered (same source+view as uml-sequence-fragments.json)
#     but is expected to converge with uml-sequence-fragments.json's own regenerated output.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

./mvnw -pl cli -am test \
  -Dtest=LayoutFixtureRegenerator \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddediren.regen-layout-fixtures=true \
  "$@"
