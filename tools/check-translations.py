#!/usr/bin/env python3
"""Validate every translated strings.xml against the German source.

Checks only the things that actually break an Android build or the running app:
  1. well-formed XML
  2. exactly the same set of string keys (nothing missing, nothing extra)
  3. identical format placeholders per key (%1$s, %1$d, %1$.1f, %%) — a mismatch is a
     guaranteed IllegalFormatException the moment that string is shown, not a cosmetic issue
  4. unescaped apostrophes, which aapt rejects outright

Run from the repo root: python3 tools/check-translations.py
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

BASE = "app/src/main/res/values/strings.xml"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[\d.]*[a-zA-Z%]")


def load(path):
    """key -> raw text. Raw matters: escaping bugs only exist in the raw form."""
    src = open(path, encoding="utf-8").read()
    ET.fromstring(src)                      # raises on malformed XML
    return dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', src, re.S))


def main():
    base = load(BASE)
    print(f"German source: {len(base)} keys\n")
    failures = 0
    for path in sorted(glob.glob("app/src/main/res/values-*/strings.xml")):
        lang = os.path.basename(os.path.dirname(path)).replace("values-", "")
        problems = []
        try:
            tr = load(path)
        except Exception as exc:
            print(f"  {lang:4s} MALFORMED XML: {exc}")
            failures += 1
            continue

        missing = sorted(set(base) - set(tr))
        extra = sorted(set(tr) - set(base))
        if missing:
            problems.append(f"{len(missing)} missing: {missing[:5]}")
        if extra:
            problems.append(f"{len(extra)} extra: {extra[:5]}")

        for key in sorted(set(base) & set(tr)):
            want = sorted(PLACEHOLDER.findall(base[key]))
            got = sorted(PLACEHOLDER.findall(tr[key]))
            if want != got:
                problems.append(f"placeholder {key}: want {want}, got {got}")
            if re.search(r"(?<!\\)'", tr[key]):
                problems.append(f"unescaped apostrophe in {key}: {tr[key][:60]!r}")

        if problems:
            failures += 1
            print(f"  {lang:4s} {len(tr):3d} keys  X")
            for p in problems[:8]:
                print(f"         - {p}")
            if len(problems) > 8:
                print(f"         ... and {len(problems) - 8} more")
        else:
            print(f"  {lang:4s} {len(tr):3d} keys  ok")

    print()
    print("ALL OK" if not failures else f"{failures} language file(s) with problems")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
