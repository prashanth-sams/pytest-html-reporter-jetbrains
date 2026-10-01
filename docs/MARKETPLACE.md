# JetBrains Marketplace listing

Copy for the plugin page. The full description is also built into the plugin from the marked section of
`README.md`, so the page and the IDE's plugin details stay the same.

## Name

pytest-html-reporter

## Short description

> View, find and generate pytest-html-reporter reports in PyCharm and IntelliJ IDEA, with the report's own
> dashboard, charts, screenshots and logs.

(Marketplace shows the first sentence of the description in search results; it is the same text.)

## Full description

See the section between `<!-- Plugin description -->` and `<!-- Plugin description end -->` in
[README.md](../README.md). It covers the tool window, report discovery, auto-refresh, the run action, version-aware
options, the install offer, local-only operation and project-trust handling, and which IDEs are supported.

## Tags

Existing Marketplace tags (checked against <https://plugins.jetbrains.com/api/tags>):

- **Testing** - primary
- **Tools Integration**
- **Viewer**
- **Reporting**

Marketplace has no `Python` tag; compatibility with PyCharm is shown from the plugin's declared dependencies.

## Links

| Field | URL |
| --- | --- |
| Source code | https://github.com/prashanth-sams/pytest-html-reporter-jetbrains |
| Issue tracker | https://github.com/prashanth-sams/pytest-html-reporter-jetbrains/issues |
| Documentation | https://github.com/prashanth-sams/pytest-html-reporter-jetbrains#readme |
| pytest-html-reporter (Python package) | https://github.com/prashanth-sams/pytest-html-reporter |
| pytest-html-reporter documentation | https://prashanth-sams.github.io/pytest-html-reporter.github.io/ |

## License

MIT - <https://github.com/prashanth-sams/pytest-html-reporter-jetbrains/blob/main/LICENSE>

## Screenshots

Captured from the plugin running in a sandbox PyCharm by `scripts/smoke-test.sh` (IDE window 2880×1800; the
settings dialog 2560×1760):

| File | Caption |
| --- | --- |
| `screenshots/report-dashboard.png` | The report's dashboard in the Pytest HTML Reporter tool window |
| `screenshots/test-metrics.png` | Test Metrics: every test with status, duration and error |
| `screenshots/screenshots-gallery.png` | Screenshots captured by failing tests, served from the report folder |
| `screenshots/api-logs.png` | API logs and attachments |
| `screenshots/after-run.png` | After **Run pytest with HTML Report**: the report of that run opens in the tool window |
| `screenshots/settings.png` | Settings &#124; Tools &#124; pytest-html-reporter |
| `screenshots/empty-state.png` | The tool window before the first report |
| `screenshots/untrusted-project.png` | An untrusted project: the report is not opened automatically (documentation, not for the listing) |
| `screenshots/jcef-unavailable.png` | Without the embedded browser: the system-browser fallback (documentation, not for the listing) |

Recommended order for the listing: `report-dashboard`, `test-metrics`, `screenshots-gallery`, `api-logs`,
`after-run`, `settings`. All were captured from PyCharm 2026.2.3 except `jcef-unavailable` (PyCharm 2025.1.6.2).

## Plugin logo

`src/main/resources/META-INF/pluginIcon.svg` and `pluginIcon_dark.svg` (40×40, the visible part within 32×32,
under 1 KB each). The four descending bars are pytest-html-reporter's own mark in its colours (#3571A3, #FFD143,
#8B8B8B), with a lighter blue and grey for dark themes.
