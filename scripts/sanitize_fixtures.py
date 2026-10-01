"""Replace machine-specific values in generated fixtures (temporary paths, host name) with neutral placeholders.

Usage: python sanitize_fixtures.py <fixture dir> <temporary work dir> [<host name>]
"""
import os
import re
import sys


def main(root, work, host=None):
    work = os.path.realpath(work)
    variants = {work, work.replace("/private/var/", "/var/", 1), "/private" + work if work.startswith("/var/") else work}
    pattern = re.compile("|".join(re.escape(v) for v in sorted(variants, key=len, reverse=True)))
    for directory, _, files in os.walk(root):
        for name in files:
            if not name.endswith((".html", ".json")):
                continue
            path = os.path.join(directory, name)
            with open(path, encoding="utf-8") as handle:
                text = handle.read()
            cleaned = pattern.sub("/tmp/fixtures", text)
            # Reports truncate long error messages mid-path, so also catch bare macOS temporary-folder prefixes.
            cleaned = re.sub(r"(/private)?/var/folders/[A-Za-z0-9_]+(/[A-Za-z0-9_]*)?", "/tmp/fixtures", cleaned)
            if host:
                cleaned = re.sub(r"(?<![\w.-])%s(?![\w.-])" % re.escape(host), "fixture-host", cleaned)
            if cleaned != text:
                with open(path, "w", encoding="utf-8") as handle:
                    handle.write(cleaned)


if __name__ == "__main__":
    main(*sys.argv[1:])
