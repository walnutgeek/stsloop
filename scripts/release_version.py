#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Turn a release tag into the APK's versionName and versionCode.

    uv run scripts/release_version.py v1.2.3
    name=1.2.3
    code=10203

The release workflow appends this output to $GITHUB_OUTPUT and passes both
values to Gradle (-Pstsloop.versionName, -Pstsloop.versionCode).

versionCode is major*10000 + minor*100 + patch, so a later tag always gets a
larger code and the APK installs over the previous one. It depends only on
the tag, so rebuilding a tag reproduces the same code. minor and patch must
stay below 100, and the code must exceed 1 (what builds before tagged
releases shipped) and stay at or below Android's 2100000000.
"""

from __future__ import annotations

import re
import sys

TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
UNTAGGED_CODE = 1  # app/build.gradle.kts default
MAX_CODE = 2_100_000_000


def release_version(tag: str) -> tuple[str, int]:
    m = TAG.fullmatch(tag)
    if not m:
        raise ValueError(f"tag {tag!r} is not vMAJOR.MINOR.PATCH")
    major, minor, patch = (int(g) for g in m.groups())
    if minor > 99 or patch > 99:
        raise ValueError(f"tag {tag!r}: minor and patch must be below 100")
    code = major * 10_000 + minor * 100 + patch
    if code <= UNTAGGED_CODE:
        raise ValueError(f"tag {tag!r}: versionCode {code} must exceed {UNTAGGED_CODE}")
    if code > MAX_CODE:
        raise ValueError(f"tag {tag!r}: versionCode {code} exceeds {MAX_CODE}")
    return m.group(0)[1:], code


def main(argv: list[str]) -> list[str]:
    if len(argv) != 1:
        raise SystemExit("usage: release_version.py vMAJOR.MINOR.PATCH")
    name, code = release_version(argv[0])
    lines = [f"name={name}", f"code={code}"]
    print("\n".join(lines))
    return lines


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except ValueError as e:
        raise SystemExit(f"error: {e}")
