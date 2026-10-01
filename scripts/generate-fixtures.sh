#!/usr/bin/env bash
# Regenerates src/test/testData from released pytest-html-reporter packages, so the plugin's tests run against
# reports, probe output and path rules produced by the real upstream code rather than hand-written imitations.
#
# Usage: scripts/generate-fixtures.sh            (uses python3 from PATH)
#        PYTHON=/path/to/python3 scripts/generate-fixtures.sh
#
# Needs network access to PyPI. Writes only under src/test/testData.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-python3}"
OUT="$ROOT/src/test/testData"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

PROBE="$ROOT/src/main/resources/python/pytest_html_reporter_probe.py"
VERSIONS=("0.4.3" "0.3.0")

mkdir -p "$OUT/reports" "$OUT/probe" "$OUT/upstream"

for version in "${VERSIONS[@]}"; do
  echo "== pytest-html-reporter $version"
  venv="$WORK/venv-$version"
  "$PYTHON" -m venv "$venv"
  "$venv/bin/python" -m pip install --quiet --disable-pip-version-check "pytest-html-reporter==$version"

  # A project path with spaces and non-ASCII characters, as users have.
  project="$WORK/fixture project $version ü"
  cp -R "$ROOT/scripts/fixture-project" "$project"

  open_flag=()
  if "$venv/bin/python" -m pytest --help 2>/dev/null | grep -q -- "--report-open"; then
    open_flag=(--report-open=none)
  fi

  status=0
  (cd "$project" && "$venv/bin/python" -m pytest --html-report="$project/report" ${open_flag[@]+"${open_flag[@]}"}) || status=$?
  if [ "$status" -ne 1 ]; then
    echo "expected pytest to exit with 1 (one failing test), got $status" >&2
    exit 1
  fi

  dest="$OUT/reports/$version"
  rm -rf "$dest"
  mkdir -p "$dest"
  cp "$project/report/pytest_html_report.html" "$project/report/output.json" "$dest/"
  if [ -d "$project/report/pytest_screenshots" ]; then
    cp -R "$project/report/pytest_screenshots" "$dest/"
  fi

  "$venv/bin/python" "$PROBE" "$project" > "$OUT/probe/reporter-$version.json"
done

echo "== probe: configured project (0.4.3)"
config_project="$WORK/configured project"
mkdir -p "$config_project"
cat > "$config_project/pytest.ini" <<'EOF'
[pytest]
html_report = reports/%Y%m%d/run.html
addopts = -q --html-report=from-addopts
EOF
cat > "$config_project/pyproject.toml" <<'EOF'
[tool.pytest-html-reporter.profiles.ci]
path = "ci-out"
open = "none"
EOF
"$WORK/venv-0.4.3/bin/python" "$PROBE" "$config_project" > "$OUT/probe/configured-0.4.3.json"
"$WORK/venv-0.4.3/bin/python" "$PROBE" "$config_project" ci > "$OUT/probe/configured-profile-ci-0.4.3.json"
"$WORK/venv-0.3.0/bin/python" "$PROBE" "$config_project" > "$OUT/probe/configured-0.3.0.json"

echo "== probe: configured project (0.3.6, html_report ini key without the config command)"
"$PYTHON" -m venv "$WORK/venv-0.3.6"
"$WORK/venv-0.3.6/bin/python" -m pip install --quiet --disable-pip-version-check "pytest-html-reporter==0.3.6"
"$WORK/venv-0.3.6/bin/python" "$PROBE" "$config_project" > "$OUT/probe/configured-0.3.6.json"

echo "== probe: interpreter with pytest but without the reporter"
"$PYTHON" -m venv "$WORK/venv-pytest-only"
"$WORK/venv-pytest-only/bin/python" -m pip install --quiet --disable-pip-version-check pytest
"$WORK/venv-pytest-only/bin/python" "$PROBE" "$WORK" > "$OUT/probe/missing-reporter.json"

echo "== upstream path rules (0.4.3)"
"$WORK/venv-0.4.3/bin/python" "$ROOT/scripts/upstream_paths.py" > "$OUT/upstream/report_paths.json"

echo "== removing machine-specific values (temporary paths, host name)"
"$PYTHON" "$ROOT/scripts/sanitize_fixtures.py" "$OUT" "$WORK" "$(hostname)"

echo "Fixtures written to $OUT"
