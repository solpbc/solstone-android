#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 sol pbc
"""Release coordinates and version allocation for the two maintained apps."""
import argparse
from pathlib import Path
import re

SURFACES = {
    "phone": {"module": "phone", "variant": "real-release", "task_variant": "RealRelease",
              "variant_dir": "realRelease", "apk_dir": "real/release", "product": "solstone-android",
              "tag_prefix": "v", "changelog": "CHANGELOG.md", "title": "solstone for Android",
              "parity": 0, "first_code": 52},
    "wear": {"module": "wear", "variant": "release", "task_variant": "Release",
             "variant_dir": "release", "apk_dir": "release", "product": "solstone-wear",
             "tag_prefix": "wear-v", "changelog": "apps/wear/CHANGELOG.md", "title": "solstone for wear os",
             "parity": 1, "first_code": 51},
}
MAX_CODE = 2_100_000_000


def validate_code(surface, code):
    config = SURFACES[surface]
    if surface == "phone" and 1 <= code <= 50:
        return
    if not config["first_code"] <= code <= MAX_CODE or code % 2 != config["parity"]:
        raise ValueError("version code is outside this surface's allocated sequence")


def next_code(surface, current):
    config = SURFACES[surface]
    if current < config["first_code"]:
        if (surface, current) not in (("phone", 50), ("wear", 1)):
            raise ValueError("unexpected historical version code")
        return config["first_code"]
    if current % 2 != config["parity"]:
        raise ValueError("version code has the other surface's parity")
    if current + 2 > MAX_CODE:
        raise ValueError("version code range exhausted")
    return current + 2


def bump(surface, version, root):
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("VERSION must be x.y.z")
    gradle = root / "apps" / SURFACES[surface]["module"] / "build.gradle.kts"
    text = gradle.read_text()
    codes = re.findall(r"versionCode = (\d+)", text)
    names = re.findall(r'versionName = "([^"]+)"', text)
    if len(codes) != 1 or len(names) != 1:
        raise ValueError("expected one literal versionCode and versionName")
    code = next_code(surface, int(codes[0]))
    text = re.sub(r"versionCode = \d+", f"versionCode = {code}", text)
    text = re.sub(r'versionName = "[^"]+"', f'versionName = "{version}"', text)
    gradle.write_text(text)
    print(f"{surface} -> versionName {version}, versionCode {code}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    config = commands.add_parser("config")
    config.add_argument("surface", choices=SURFACES)
    config.add_argument("key", choices=next(iter(SURFACES.values())).keys())
    check = commands.add_parser("check-code")
    check.add_argument("surface", choices=SURFACES)
    check.add_argument("code", type=int)
    version = commands.add_parser("bump")
    version.add_argument("surface", choices=SURFACES)
    version.add_argument("version")
    version.add_argument("--root", type=Path, default=Path.cwd())
    args = parser.parse_args()
    try:
        if args.command == "config":
            print(SURFACES[args.surface][args.key])
        elif args.command == "check-code":
            validate_code(args.surface, args.code)
        else:
            bump(args.surface, args.version, args.root)
    except ValueError as error:
        parser.exit(2, f"release surface: {error}\n")


if __name__ == "__main__":
    main()
