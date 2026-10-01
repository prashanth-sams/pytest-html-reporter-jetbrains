# Verification report - pytest-html-reporter 0.1.0

What was checked for this release, how, and with what result. Everything here was run on 2026-10-01 against the
build in `build/distributions/pytest-html-reporter-0.1.0.zip`. Items that were **not** verified are listed at the end.

## Environment

| | |
| --- | --- |
| Host | macOS 15.7.4, Apple silicon (arm64) |
| Build | Gradle 9.8.0 (wrapper, SHA-256 checked), IntelliJ Platform Gradle Plugin 2.19.0, Kotlin 2.4.20, JDK 21 |
| SDK compiled against | PyCharm Community 2025.1.6.2 (PC-251.29188.66), the oldest supported build |
| Test runtime | JetBrains Runtime 21.0.8 with JCEF (b895.146) |
| Plugin Verifier | 1.410 |
| Interpreters | CPython 3.14.6 (Homebrew), CPython 3.9.6 (Apple, with pytest 7.4.4 and no reporter) |
| pytest | 9.1.1 |
| pytest-html-reporter | 0.4.3 (latest release, = upstream `master`), 0.3.7, 0.3.6, 0.3.0, 0.2.9 |

## 1. Upstream behaviour the plugin relies on

Checked by running the released packages, not only by reading the code.

| Fact | How it was checked | Result |
| --- | --- | --- |
| Default output location | `pytest` with no reporter options, 0.4.3 | `./pytest_html_report.html`, `./output.json`, `./pytest_screenshots/` in the working directory. The README's `report/pytest_html_reporter.html` does not match the code (`util.REPORT_PATH_DEFAULT = "."`, `HTMLReporter.report_path`). |
| `--html-report` with spaces and non-ASCII | `--html-report="<dir>/out dir ü/my report ü.html"` | Written there; a second run moves the previous `output.json` to `archive/output_<timestamp>.json`. |
| Option availability by release | `pytest --help` in venvs for 0.2.9, 0.3.0, 0.3.6, 0.3.7, 0.4.3 | `--report-open` from 0.3.7; `--report-profile` and the `config` command from 0.4.3. |
| Path rules | `scripts/upstream_paths.py` runs upstream's `expand_time` and `HTMLReporter.report_path` at a frozen time | 20 cases recorded in `src/test/testData/upstream/report_paths.json`; the Kotlin mirror matches all of them (`ReportPathsTest`). |
| Configuration resolution | `pytest-html-reporter config --json` with an ini key, a profile, an environment variable and a bad profile | Path and source reported for each; exit 2 with a message for an unknown profile; `addopts` is not modelled (by design). |
| Precedence | `addopts = --html-report=x` plus `html_report = y` | `addopts` wins. |
| Runs without results | `-k nomatch` (exit 5) and `--collect-only` | No report written. |
| Oldest release | 0.2.9 on pytest 9.1.1 | `INTERNALERROR` (`TerminalReporter._sessionstarttime`), no report → minimum supported version 0.3.0. |
| 0.3.0 | fixture suite | Report written; `attach()` raises `AttributeError: HTMLReporter has no attribute 'base_path'` (works in 0.3.6). |
| Report signature | head of generated reports (0.3.0, 0.4.3) | `<title>Pytest HTML Reporter</title>` within the first 3 KB; complete files end with `</html>`. |

## 2. Build and static checks

```text
./gradlew clean :buildPlugin :test :verifyPluginStructure :verifyPluginProjectConfiguration   -> BUILD SUCCESSFUL
```

- `buildPlugin`: `build/distributions/pytest-html-reporter-0.1.0.zip`. It is 375,882 bytes and holds one JAR: 176
  classes, the descriptors, icons, message bundle and probe script, and `META-INF/LICENSE` and `META-INF/NOTICE.md`. It
  bundles no libraries; the Kotlin standard library comes from the IDE.
  SHA-256 `17e418ae8ac7de3369881768fb1feeddff3a00e09fb6a191f00f98f40d14fda5`.
- Reproducible: `./gradlew clean :buildPlugin --no-build-cache --rerun-tasks` rebuilds a byte-identical ZIP.
- `verifyPluginStructure`: no problems.
- `verifyPluginProjectConfiguration`: no problems (an earlier `until-build` was removed on its advice).
- Compiler warnings: none (Kotlin API version 2.1 is required for 2025.1's bundled stdlib; its deprecation note is
  suppressed deliberately).

## 3. Automated tests - 88 tests, 0 failures

`PYTEST_HTML_REPORTER_PYTHON=<venv with 0.4.3> PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER=/usr/bin/python3 ./gradlew :test`

The end-to-end tests return early, and still count as passed, when these variables are unset. In the final run they
executed: the test IDE's log has no "is not set; skipping" warning and records the kill of the run that ignores
SIGINT. The only warning in that log is the expected "JCEF-sandbox was disabled" for headless tests.

| Test class | Tests | Covers |
| --- | ---: | --- |
| `ReportPathsTest` | 6 | Upstream path parity (20 recorded cases), strftime expansion, escaping, patterns, `$VAR`/`~` expansion |
| `PytestCommandBuilderTest` | 16 | Command construction, spaces/Unicode, `--` handling, option gating by installed version, configuration precedence (CLI > addopts > profile/env/ini > settings), conflicts |
| `PytestArgumentsTest` | 5 | Option parsing, `-p no:reporter` spellings, collect-only, shards, `--html` |
| `ProbeResultTest` | 8 | Probe output recorded from real interpreters (0.4.3, 0.3.6, 0.3.0, no reporter, profiles) |
| `ReportDiscoveryTest` | 10 | Recognition of real 0.3.0/0.4.3 reports, partial files, venv/conda/dependency/build exclusions, symlinks, depth and folder limits, run-to-report matching, completeness wait |
| `ReportNavigationPolicyTest` | 7 | URL encoding, fragments, external links, local files, blocked schemes, subresource containment |
| `RunOutcomeTest`, `ReporterVersionTest`, `MiniJsonTest`, `PythonActivationTest`, `MessageBundleTest` | 18 | Summaries from real `output.json`, exit codes, version ordering, JSON parsing, venv/conda activation, message keys and apostrophes |
| `ReporterSettingsTest` | 5 | Defaults, XML round trip (incl. Unicode), storage split (shared vs `workspace.xml`) |
| `ReportDiscoveryServiceTest` | 3 | Background scan, published state, incremental refresh, known locations |
| `JcefReportRenderingTest` | 1 | Real 0.4.3 report in headless JCEF through the production browser class: page title, 10 chart canvases, jQuery/DataTables initialised, a screenshot beside the report loads from disk (4 px PNG), a PNG outside the report folder is refused, reload keeps `#test-metrics` |
| `PytestRunServiceTest` | 7 | Real pytest runs: failing run shows its new report (stale one replaced), run without results keeps the previous report and says so, duplicate run refused, passing run with a Unicode environment variable, **Stop** interrupts and the partial report is shown (exit 2), Stop kills a run that ignores SIGINT, missing reporter offers the install command (Python 3.9) |
| `PytestRunConfigurationListenerTest` | 2 | Opt-in refresh after a real PyCharm pytest run configuration's execution events; off by default |

The end-to-end class also passed with `PYTEST_HTML_REPORTER_PYTHON` pointing at pytest-html-reporter **0.3.0**
(no `--report-open`, no attachments).

## 4. Plugin Verifier

Plugin Verifier 1.410, one IDE per run: `./gradlew :verifyPlugin -PverifyIdes=<type>:<version>` (the IDEs in
`verifyIdes`), plus two macOS installer layouts with `-PverifyUseInstaller=true`.

| IDE | Build | Layout | Optional dependencies resolved | Result |
| --- | --- | --- | --- | --- |
| PyCharm Community 2025.1.6.2 | PC-251.29188.66 | multi-OS archive | Python (JCEF is part of the platform before 2026.2) | **Compatible** |
| PyCharm Community 2025.2.6.2 | PC-252.28539.90 | multi-OS archive | Python | **Compatible** |
| PyCharm 2025.3.6.1 | PY-253.33813.50 | multi-OS archive | Python | **Compatible** |
| PyCharm 2026.1.5 | PY-261.27258.51 | multi-OS archive | Python | **Compatible** |
| PyCharm 2026.2.3 | PY-262.10968.92 | multi-OS archive | Python, JCEF | **Compatible**, 1 deprecated API usage |
| IntelliJ IDEA 2026.2.3 | IU-262.10968.63 | multi-OS archive | Python (the Python plugin, which the verifier downloaded from Marketplace), JCEF | **Compatible**, 1 deprecated API usage |
| PyCharm 2026.2.3 | PY-262.10968.92 | macOS installer | JCEF; Python **not** resolved by the verifier | 1 compatibility problem (verifier gap, below), 1 deprecated API usage |
| PyCharm 2026.3 EAP | PY-263.6259.38 | macOS installer | JCEF; Python **not** resolved by the verifier | 1 compatibility problem (verifier gap, below), 1 deprecated API usage |

Every run also reports *"Plugin can probably be enabled or disabled without IDE restart"* (dynamic plugin).

**Deprecated API usage (2026.2+).** `ReporterConfigurable` creates the environment-variables field with the
`EnvironmentVariablesComponent()` constructor, deprecated in 2026.2 but not scheduled for removal. Its replacement does
not exist in 2025.1, the oldest supported build, so the call stays until `pluginSinceBuild` is raised.

**Verifier gap for installer layouts.** On both macOS installers the verifier reports
`com.jetbrains.python.sdk.PythonSdkUtil` as unresolved. The class is used only by `python/PythonSdkInterpreterProvider`,
which the IDE loads only when the optional `com.intellij.modules.python` dependency is present. The verifier's
dependency tree shows why: it looks for that module in a content module of the Shell Script plugin
(`intellij.sh.python` of `com.jetbrains.sh`), fails, and marks the dependency *(failed) … (optional)*. It labels the
result itself: "some of which may be caused by absence of optional dependency". It is not a plugin problem:

- the same PyCharm 2026.2.3 build verifies as Compatible from the multi-OS archive, where `PythonCore` resolves;
- the installed PyCharm 2026.2.3 used by `runSmokeTest262` resolves the project interpreter through exactly this class
  (section 5, row "Project interpreter").

**2026.3 EAP.** The first EAP check found calls of `BrowserUtil.browse(File)`, which is scheduled for removal. They
now use the `Path` overload, and the final EAP check reports no scheduled-for-removal usages.

**Which build was checked.** The matrix above ran on the 0.1.0 ZIP as it was before `LICENSE` and `NOTICE.md` were
added to the JAR's `META-INF/` (374,117 bytes). The final ZIP differs from it only by those two text files. It was
checked again against the oldest and the newest stable targets: PyCharm Community 2025.1.6.2 **Compatible**; PyCharm
2026.2.3 and IntelliJ IDEA 2026.2.3, the latter with the Python plugin, **Compatible** with the same deprecated API
usage.

## 5. Development-IDE smoke tests

`scripts/smoke-test.sh` starts a sandbox IDE with the plugin and the test-only `smoke-test` plugin, which drives the
real workflow and records `results.json` (copies in `docs/screenshots/smoke-results-*.json`). The project lives in
`trusted ü/sample project ü/` with its own `.venv` (pytest-html-reporter 0.4.3); only that folder is pre-trusted, so
the IDE's trust check stays on. Each full run starts without SDKs, so the IDE configures the `.venv` itself, as when a
user first opens the project. The harness creates an SDK of its own only if the IDE has not done so within 30 s; it
was not needed in the final runs.

The final runs used the final build (ZIP SHA-256 `17e418ae…`):

| Step | PyCharm Community 2025.1.6.2 | PyCharm 2026.2.3 |
| --- | --- | --- |
| Environment | JCEF in the platform; `JBCefBrowser` loads through the plugin class loader | JCEF from the separate `com.intellij.modules.jcef` plugin, loaded through the optional dependency; JBR 25, CEF 144 |
| Project interpreter, configured by the IDE, resolved through the Python integration (path with spaces and `ü`) | ✓ SDK "Python 3.14 (sample project ü)" | ✓ SDK named after the `.venv` path |
| Tool window empty state | ✓ | ✓ |
| **Run pytest with HTML Report** (exit 1) → report of that run shown, outcome notification | ✓ 7.1 s | ✓ 9.0 s |
| Report pages (dashboard, test metrics, screenshots, API logs) rendered offline | ✓ | ✓ |
| Report rewritten from outside the IDE → auto-refresh, page (`#test-metrics`) kept (step includes a 1 s settle) | ✓ 3.6 s | ✓ 3.3 s |
| Second report → listed in the selector | ✓ | ✓ |
| Settings page opens and closes | ✓ | ✓ |
| Untrusted project → report not opened automatically, run refused | ✓ | ✓ |
| Project close → disposed cleanly | ✓ | ✓ |
| Errors logged by the plugin | none | none |

JCEF disabled (`-Dide.browser.jcef.enabled=false`, PyCharm 2025.1.6.2): the viewer shows the "embedded browser
unavailable" page with **Open in External Browser** - ✓.

Platform messages seen in the sandbox logs that are not plugin errors:

- PyCharm 2026.2.3 logs `WARN RunContentManagerImpl - ContentDescriptorId was not assigned to descriptor` for the
  pytest console. The console is opened through the platform's `RunContentExecutor`, which in 2026.2.3 creates its
  descriptor without an ID and offers no way to set one.
- An earlier version of the harness created a bare SDK itself. PyCharm 2026.2.3's own widgets reject such an SDK
  (`IllegalStateException … Please use … setupSdk`, status bar "&lt;No interpreter&gt;"), while the plugin still
  resolved it. In a first-run 2025.1 sandbox, the platform also reloaded its SDK table from disk a few seconds after
  startup and dropped an SDK created earlier. The settings page then correctly showed "once one is configured". Both
  led to the harness change described above.

Screenshots in `docs/screenshots/` come from these runs. They are painted from the IDE's own windows, with the
browser area taken from Chromium's own frame; CEF's frame matched the component exactly at 2× (e.g. 2716×1438 for
1358×719).

### Problems found by the smoke tests and fixed

| Found | Fix |
| --- | --- |
| The settings page looked up the module interpreter on the UI thread (`SlowOperations` error) | Looked up in a non-blocking read action |
| On macOS the IDE's file watcher delivered no events for a report under a folder named with `ü`; 2026.2.3 warned it could not watch the project at all | Events are matched NFC-normalised and by `VirtualFile`; while the viewer is visible with auto-refresh on, the shown file's size and timestamp are also checked every 2 s |
| When Chromium failed to start, the viewer showed a spinner forever | 30 s load watchdog → error page with **Retry** and **Open in External Browser** |
| **Stop** (notification action) had no effect when SIGINT is ignored, as for an IDE started in the background | Interrupt first, kill after 10 s |
| `''` shown literally in messages without parameters | Fixed; `MessageBundleTest` checks every message |

### JCEF on 2026.2+ from Gradle archives

Since 2026.2 the JCEF plugin loads Chromium from `plugins/jcef-plugin/jcef`, which the multi-OS archives in the
IntelliJ Maven repository do not contain. A sandbox IDE built from such an archive therefore cannot start JCEF (the
IDE's own startup check fails). This affects development runs only; `runSmokeTest262` uses the macOS installer.

## 6. Not verified

- **Other host operating systems.** Runtime behaviour was only exercised on macOS (arm64). The GitHub Actions
  workflows (`build.yml`, `release.yml`) are written for Linux but have never run: the project has no GitHub
  repository yet. Windows has not been run at all. Windows-specific code paths (`Scripts` folders, `;` in `PATH`,
  separators) are unit-tested only.
- **IntelliJ IDEA at runtime.** IntelliJ IDEA 2026.2.3 passed the Plugin Verifier together with the Python plugin
  (section 4), but the plugin was never started in IntelliJ IDEA; the Python integration was exercised in PyCharm
  only.
- **Remote interpreters** (SSH, Docker, WSL, …): detected and explained, but not exercised against a real remote
  SDK.
- **Conda environments** and Python 3.7, 3.8 and 3.10-3.13: activation logic unit-tested only; interpreters not run.
- **Exports from the report** (CSV, Excel, PDF downloads) and the report's copy-to-clipboard buttons inside the
  embedded viewer were not exercised; printing is not supported in the viewer (documented).
- **Network file systems** and reports on drives the IDE does not watch: covered by the polling fallback in design,
  not tested.
- **2026.3 EAP**: Plugin Verifier only, no runtime check.
