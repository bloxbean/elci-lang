#!/usr/bin/env python3
"""Validate a release tag against the committed Gradle metadata."""
import os
from pathlib import Path
import re

properties = {}
for line in Path("gradle.properties").read_text().splitlines():
    line = line.strip()
    if line and not line.startswith(("#", "!")) and "=" in line:
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()

version = properties["version"]
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version):
    raise SystemExit(f"Unsupported release version: {version!r}")
if os.environ["GITHUB_REF_NAME"] != f"v{version}":
    raise SystemExit(f"Tag must be v{version}, matching gradle.properties")
if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z._-]*", properties["julcVersion"]):
    raise SystemExit("Invalid julcVersion")
if not re.fullmatch(r"[0-9a-f]{40}", properties["julcRevision"]):
    raise SystemExit("julcRevision must pin a full commit SHA")
with open(os.environ["GITHUB_OUTPUT"], "a") as output:
    for key in ("version", "julcVersion", "julcRevision"):
        print(f"{key}={properties[key]}", file=output)
