# Changelog

All notable changes to the pytest-html-reporter JetBrains plugin are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/). The section matching `pluginVersion` in `gradle.properties` becomes the
plugin's change notes on JetBrains Marketplace.

## [Unreleased]

## [0.1.0] - 2026-10-01

### Added

- **Pytest HTML Reporter** tool window that shows pytest-html-reporter reports in the IDE's embedded browser (JCEF),
  using the report's own dashboard, suites, metrics, charts, filters, screenshots, logs, attachments, steps,
  analytics, archives and themes.
- Report discovery: a bounded background search of the project that skips virtual environments (by their
  `pyvenv.cfg` or `conda-meta` marker as well as by name), dependency folders and build output; afterwards only the
  folders named by file-system events are re-checked.
- Report selector listing every report found, with its relative path and modification time; the selection is
  remembered per project. **Open Report…** picks any report file.
- Auto-refresh that waits until a report is completely written and keeps the report page (URL fragment) on screen.
- **Run pytest with HTML Report**, which runs
  `python -m pytest <targets> <arguments> --html-report=<output> --report-open=none` with the project interpreter (or
  one set in settings), shows the output in the Run tool window and opens the report that run wrote - after failing
  runs too, and never an older report for a run that wrote none. **Stop** interrupts pytest so the report of the tests
  that ran is still written, and ends it if the interrupt is ignored.
- Capability checks against the installed pytest-html-reporter (minimum 0.3.0): `--report-open` is only passed when
  the installed version registers it, and the report location configured in `pytest.ini`, `pyproject.toml`,
  profiles, `PYTEST_HTML_REPORTER_*` variables or `addopts` is respected.
- Detection of arguments that conflict with the reporter (`-p no:reporter`, `--collect-only`, shard legs, `--html`,
  `--report-open`), and an offer to install pytest-html-reporter when it is missing.
- Opt-in refresh of the viewer after pytest run configurations finish.
- **Open in External Browser**, **Reveal Report File**, **Toggle Auto-refresh**, **Refresh** and **Rescan for
  Reports** actions; a Tools menu group; **Open in Pytest HTML Reporter** in the Project view and editor tab menus.
- Settings | Tools | pytest-html-reporter: interpreter, working directory, targets, arguments, environment
  variables, output path, viewer behaviour and discovery scope.
- Containment of report content: subresources only from the report's folder, remote content blocked by default,
  web links routed to the system browser, no JavaScript bridge into the IDE.
- Project trust: untrusted projects never run Python and do not open reports automatically.
- System-browser fallback when the embedded browser is unavailable.
