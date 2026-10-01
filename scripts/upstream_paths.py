"""Record how the installed pytest-html-reporter resolves --html-report values.

Writes JSON cases computed by upstream's own code (util.expand_time and HTMLReporter.report_path) at a frozen
time, so the plugin's Kotlin mirror (ReportPaths) is tested against the real implementation, quirks included.

Usage: python upstream_paths.py > src/test/testData/upstream/report_paths.json
"""
import datetime as real_datetime
import json
import os
import sys
import types

from pytest_html_reporter import util
from pytest_html_reporter.html_reporter import HTMLReporter

FROZEN = real_datetime.datetime(2026, 3, 7, 9, 5, 4, 123456)


class FrozenDatetime(real_datetime.datetime):
    @classmethod
    def now(cls, tz=None):
        return FROZEN


util.datetime = FrozenDatetime

CASES = [
    "",
    ".",
    "report",
    "report/",
    "./report",
    "./report/report.html",
    "report.html",
    "/report.html",
    "out dir ü/my report ü.html",
    "reports/%Y%m%d/report_%H%M.html",
    "reports/%Y-%m-%d_%H-%M-%S",
    "100% pass/report.html",
    "%%Y/literal.html",
    "%q/unknown-directive",
    "a.html.d/x",
    "nested/dir.html/inner/file.html",
    "%a %A %b %B %d %f %G %H %I %j %m %M %p %S %u %U %V %w %W %y %Y %z %Z/r.html",
    "%c/r.html",
    "%x/r.html",
    "%X/r.html",
]


def resolve(value):
    expanded = util.expand_time(value or util.REPORT_PATH_DEFAULT)
    holder = types.SimpleNamespace(path=expanded)
    folder, file_name = HTMLReporter.report_path.fget(holder)
    return {
        "input": value,
        "expanded": expanded,
        "folder": os.path.relpath(folder, os.getcwd()).replace(os.sep, "/"),
        "file": file_name,
    }


def main():
    json.dump(
        {
            "reporterVersion": __import__("pytest_html_reporter").__version__,
            "frozenTime": FROZEN.isoformat(),
            "cases": [resolve(case) for case in CASES],
        },
        sys.stdout,
        indent=2,
        ensure_ascii=False,
    )
    sys.stdout.write("\n")


if __name__ == "__main__":
    main()
