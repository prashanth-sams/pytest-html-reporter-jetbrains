#!/usr/bin/env bash
# Development-IDE smoke test: launches a sandbox IDE with the plugin and the test-only smoke-test plugin, which
# drives the real workflow (empty state, run pytest, report pages, auto-refresh, second report, settings,
# untrusted project, project close) and writes results.json plus screenshots.
#
# Usage: scripts/smoke-test.sh [runSmokeTest|runSmokeTest262] [full|fallback]
#   runSmokeTest     PyCharm Community 2025.1.6.2 (the oldest supported build; default)
#   runSmokeTest262  PyCharm 2026.2.3 (JCEF as a separate plugin)
#   fallback         starts the IDE with JCEF disabled and checks the system-browser fallback
#                    (needs a previous "full" run of the same task, which leaves a report behind)
#
# Needs a desktop session (the IDE opens a window) and network access to PyPI.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TASK="${1:-runSmokeTest}"
MODE="${2:-full}"
PYTHON="${PYTHON:-python3}"
WORK="$ROOT/build/smoke/$TASK"
# SMOKE_ASCII=1 uses a folder without non-ASCII characters (spaces are kept).
if [ "${SMOKE_ASCII:-0}" = "1" ]; then
  TRUSTED="$WORK/trusted ascii"
  PROJECT="$TRUSTED/sample project"
else
  TRUSTED="$WORK/trusted ü"
  PROJECT="$TRUSTED/sample project ü"
fi
OUTPUT="$WORK/results-$MODE${SMOKE_ASCII:+-ascii}"

if [ "$MODE" = "full" ]; then
  rm -rf "$TRUSTED"
  mkdir -p "$PROJECT"
  cp -R "$ROOT/scripts/fixture-project/." "$PROJECT/"
  "$PYTHON" -m venv "$PROJECT/.venv"
  "$PROJECT/.venv/bin/python" -m pip install --quiet --disable-pip-version-check "pytest-html-reporter==${REPORTER_VERSION:-0.4.3}"
  # No SDKs from earlier runs: the IDE configures the fresh .venv itself, as on a first open of the project.
  for stale in "$ROOT"/.intellijPlatform/sandbox/*/*/config_"$TASK"/options/jdk.table.xml \
               "$ROOT"/.intellijPlatform/sandbox/*/*/system_"$TASK"/global-model-cache; do
    rm -rf "$stale"
  done
fi
rm -rf "$OUTPUT"
mkdir -p "$OUTPUT"

# The sandbox resolves the smoke-test plugin's ZIP before running tasks, so build it first.
"$ROOT/gradlew" -p "$ROOT" :smoke-test:buildPlugin --console=plain -q

"$ROOT/gradlew" -p "$ROOT" "$TASK" \
  -PsmokeProject="$PROJECT" \
  -PsmokeOutput="$OUTPUT" \
  -PsmokePython="$PROJECT/.venv/bin/python" \
  -PsmokeMode="$MODE" \
  --console=plain

echo "== results ($OUTPUT/results.json)"
cat "$OUTPUT/results.json"
ls -1 "$OUTPUT"

# Fail when a step failed or the plugin logged an error in the sandbox IDE.
if grep -q '"ok": false' "$OUTPUT/results.json"; then
  echo "smoke test: a step failed" >&2
  exit 1
fi
LOG="$(find "$ROOT/.intellijPlatform/sandbox" -path "*log_$TASK*" -name idea.log 2>/dev/null | head -1 || true)"
if [ -n "$LOG" ] && grep -E "ERROR .*(pytesthtmlreporter|JcefReportBrowser|ReportViewer)" "$LOG"; then
  echo "smoke test: errors logged by the plugin in $LOG" >&2
  exit 1
fi
echo "smoke test: passed"
