"""Fixture suite for the JetBrains plugin's tests: one test per pytest outcome, plus a screenshot and attachments.

Run by scripts/generate-fixtures.sh against released pytest-html-reporter versions. Helpers that a release does
not have yet (attach_json and attach_text arrived after 0.3.0) are skipped rather than failing the import.
"""
import struct
import zlib

import pytest

try:
    from pytest_html_reporter import attach
except ImportError:  # pragma: no cover - every supported release has attach()
    attach = None

try:
    from pytest_html_reporter import attach_json, attach_text
except ImportError:
    attach_json = attach_text = None


def _png(width=4, height=4, rgb=(220, 40, 40)):
    """A tiny valid PNG, so the fixture needs no browser."""
    raw = b"".join(b"\x00" + bytes(rgb) * width for _ in range(height))

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


@pytest.fixture
def broken_fixture():
    raise RuntimeError("fixture setup exploded")


def test_passes():
    print("hello from a passing test")
    assert 1 + 1 == 2


def test_fails_with_screenshot():
    if attach is not None:
        attach(data=_png())
    if attach_text is not None:
        attach_text("SELECT * FROM orders WHERE id = 4711", name="Query", format="sql")
    if attach_json is not None:
        attach_json({"expected": {"id": 4711}, "got": {"error": "sku unknown"}, "token": "secret"}, name="Diff")
    assert {"id": 4711} == {"error": "sku unknown"}


@pytest.mark.skip(reason="demonstrates a skipped outcome")
def test_skipped():
    pass


def test_errors_in_setup(broken_fixture):
    pass


@pytest.mark.xfail(reason="known bug")
def test_expected_failure():
    assert False


def test_unicode_ü_名前():
    assert "ü" == "ü"
