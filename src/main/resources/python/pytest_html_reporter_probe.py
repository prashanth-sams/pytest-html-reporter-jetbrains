"""Environment probe for the pytest-html-reporter JetBrains plugin.

Prints one JSON document to stdout describing the interpreter it runs in: the Python, pytest and
pytest-html-reporter versions, the command-line flags the installed reporter registers, and where pytest
configuration says the report goes. It never collects or runs tests and never writes files.

Usage: python pytest_html_reporter_probe.py <working directory> [<report profile>]

Kept compatible with Python 3.7, the oldest interpreter pytest-html-reporter declares support for.
"""

import io
import json
import os
import shlex
import sys

PROTOCOL = 1

# The config files pytest considers, in its own order of preference, with the section that makes each count.
# pytest.ini counts even without a [pytest] section; pyproject.toml only with [tool.pytest.ini_options] (or the
# native [tool.pytest] table of pytest 9); tox.ini and setup.cfg only with their pytest sections.
CONFIG_CANDIDATES = (
    ("pytest.toml", "toml:pytest"),
    (".pytest.toml", "toml:pytest"),
    ("pytest.ini", "ini:pytest?"),
    (".pytest.ini", "ini:pytest?"),
    ("pyproject.toml", "toml:tool.pytest"),
    ("tox.ini", "ini:pytest"),
    ("setup.cfg", "ini:tool:pytest"),
)


def describe(error):
    return "%s: %s" % (type(error).__name__, error)


def distribution_version(name):
    try:
        from importlib import metadata
    except ImportError:  # Python 3.7
        try:
            import importlib_metadata as metadata
        except ImportError:
            metadata = None
    if metadata is not None:
        try:
            return metadata.version(name)
        except Exception:
            return None
    try:
        import pkg_resources

        return pkg_resources.get_distribution(name).version
    except Exception:
        return None


class OptionRecorder(object):
    """Stands in for pytest's parser so that the reporter's pytest_addoption can list its flags."""

    def __init__(self):
        self.options = []

    def getgroup(self, *args, **kwargs):
        return self

    def addoption(self, *names, **kwargs):
        self.options.extend(name for name in names if isinstance(name, str))

    _addoption = addoption

    def addini(self, *args, **kwargs):
        pass


def reporter_options(result):
    try:
        from pytest_html_reporter import plugin
    except Exception as error:
        result["reporterError"] = describe(error)
        return
    recorder = OptionRecorder()
    try:
        plugin.pytest_addoption(recorder)
    except Exception as error:
        result["reporterOptionsError"] = describe(error)
        return
    result["reporterOptions"] = sorted(set(recorder.options))


def resolved_report_path(result, root, profile):
    """Ask the reporter itself (0.4.3+) where its configuration puts the report: ini key, profile or environment."""
    if "--report-profile" not in result.get("reporterOptions", []):
        return
    try:
        from pytest_html_reporter import cli
    except Exception as error:
        result["configError"] = describe(error)
        return
    argv = ["config", "--json", root]
    if profile:
        argv += ["--profile", profile]
    out, err = io.StringIO(), io.StringIO()
    saved = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = out, err
    try:
        code = cli.main(argv)
    except SystemExit as exit_:
        code = exit_.code
    except Exception as error:
        code = describe(error)
    finally:
        sys.stdout, sys.stderr = saved
    if code not in (0, None):
        result["configError"] = (err.getvalue().strip() or out.getvalue().strip() or str(code))[:2000]
        return
    try:
        payload = json.loads(out.getvalue())
    except ValueError as error:
        result["configError"] = describe(error)
        return
    for setting in payload.get("settings", []):
        if setting.get("setting") == "path" and setting.get("value"):
            result["configPath"] = {"value": setting.get("value"), "source": setting.get("source", "")}


def toml_loader():
    try:
        import tomllib

        return tomllib.load
    except ImportError:
        pass
    try:
        import tomli

        return tomli.load
    except ImportError:
        return None


def read_addopts(path, kind):
    """(applies, addopts tokens, html_report value) for one candidate config file."""
    if kind.startswith("toml:"):
        loader = toml_loader()
        if loader is None:
            return False, [], None
        try:
            with open(path, "rb") as handle:
                data = loader(handle)
        except Exception:
            return False, [], None
        table = data
        for key in kind[len("toml:"):].split("."):
            table = table.get(key) if isinstance(table, dict) else None
        if not isinstance(table, dict):
            return False, [], None
        if "ini_options" in table and isinstance(table["ini_options"], dict):
            table = table["ini_options"]
        value = table.get("addopts", "")
        html_report = table.get("html_report")
    else:
        try:
            import configparser
        except ImportError:
            return False, [], None
        section = kind[len("ini:"):]
        optional = section.endswith("?")
        section = section.rstrip("?")
        parser = configparser.ConfigParser(interpolation=None, strict=False)
        try:
            with io.open(path, "r", encoding="utf-8") as handle:
                parser.read_file(handle)
        except Exception:
            return optional, [], None
        if not parser.has_section(section):
            return optional, [], None
        value = parser.get(section, "addopts", fallback="")
        html_report = parser.get(section, "html_report", fallback=None)
    if html_report is not None:
        html_report = str(html_report).strip() or None
    if isinstance(value, (list, tuple)):
        return True, [str(item) for item in value], html_report
    try:
        return True, shlex.split(str(value)), html_report
    except ValueError:
        return True, [], html_report


def find_config(start):
    """(config file, addopts tokens, html_report) for the file pytest would use, walking up from start."""
    directory = os.path.abspath(start)
    while True:
        for name, kind in CONFIG_CANDIDATES:
            path = os.path.join(directory, name)
            if os.path.isfile(path):
                applies, tokens, html_report = read_addopts(path, kind)
                if applies:
                    return path, tokens, html_report
        parent = os.path.dirname(directory)
        if parent == directory:
            return None, [], None
        directory = parent


def html_report_value(tokens):
    value = None
    for index, token in enumerate(tokens):
        if token == "--":
            break
        if token == "--html-report" and index + 1 < len(tokens):
            value = tokens[index + 1]
        elif token.startswith("--html-report="):
            value = token.split("=", 1)[1]
    return value


def addopts_report_path(result, root):
    """--html-report given through addopts or PYTEST_ADDOPTS, which the reporter's config command cannot see."""
    path, tokens, html_report = find_config(root)
    if html_report:
        # The html_report ini key (0.3.4+), for releases whose config command cannot be asked (before 0.4.3).
        result["iniReportPath"] = {"value": html_report, "source": "the html_report ini key in %s" % path}
    found = None
    value = html_report_value(tokens)
    if value is not None:
        found = {"value": value, "source": "addopts in %s" % path}
    try:
        env_tokens = shlex.split(os.environ.get("PYTEST_ADDOPTS", ""))
    except ValueError:
        env_tokens = []
    env_value = html_report_value(env_tokens)
    if env_value is not None:
        found = {"value": env_value, "source": "the PYTEST_ADDOPTS environment variable"}
    if found is not None:
        result["addoptsReportPath"] = found
    if path:
        result["configFile"] = path


def main(argv):
    root = argv[1] if len(argv) > 1 else os.getcwd()
    profile = argv[2] if len(argv) > 2 else ""
    result = {
        "protocol": PROTOCOL,
        "python": {
            "version": "%d.%d.%d" % sys.version_info[:3],
            "executable": sys.executable,
            "implementation": getattr(sys, "implementation", None) and sys.implementation.name,
        },
    }
    try:
        import importlib.util

        result["pipAvailable"] = importlib.util.find_spec("pip") is not None
    except Exception:
        result["pipAvailable"] = False
    try:
        import pytest

        result["pytestVersion"] = getattr(pytest, "__version__", None) or distribution_version("pytest")
    except Exception as error:
        result["pytestError"] = describe(error)
    result["reporterVersion"] = distribution_version("pytest-html-reporter")
    if result["reporterVersion"] and "pytestVersion" in result:
        reporter_options(result)
        resolved_report_path(result, root, profile)
    try:
        addopts_report_path(result, root)
    except Exception as error:
        result["addoptsError"] = describe(error)
    sys.stdout.write(json.dumps(result))
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
