#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "${script_dir}/.." && pwd -P)"

cd "${repo_root}"

export PLAYWRIGHT_BROWSERS_PATH="${repo_root}/.cache/playwright"
mkdir -p "${PLAYWRIGHT_BROWSERS_PATH}"

"${repo_root}/mvnw" -pl engines/render -am test -Prender-paint \
  -Dtest=BrowserInstallerTest \
  -Dsurefire.failIfNoSpecifiedTests=false

paint_test_selector="BrowserPaintSmokeTest,RasterDiffTest,RasterGoldenTest,SvgPaintAudit*Test"
for argument in "$@"; do
  if [[ "${argument}" == -Dtest=* ]]; then
    paint_test_selector=""
    break
  fi
done

test_arguments=()
if [[ -n "${paint_test_selector}" ]]; then
  test_arguments+=("-Dtest=${paint_test_selector}")
fi

exec "${repo_root}/mvnw" -pl engines/render -am test -Prender-paint \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "${test_arguments[@]}" \
  "$@"
