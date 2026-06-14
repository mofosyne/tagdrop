#!/usr/bin/env python3
"""Tap the center of the first on-screen element whose text or
content-description contains the given string (case-insensitive),
via `adb shell uiautomator dump` + `adb shell input tap`.

If nothing matches, prints every visible text/content-desc on screen
(for debugging) and exits non-zero.

Usage: _ui_tap.py <text>
"""
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

DUMP_PATH = "/data/local/tmp/window_dump.xml"


def adb(*args):
    subprocess.run(["adb", *args], check=True, capture_output=True)


def dump_tree():
    adb("shell", "uiautomator", "dump", DUMP_PATH)
    with tempfile.NamedTemporaryFile(suffix=".xml") as tmp:
        adb("pull", DUMP_PATH, tmp.name)
        return ET.parse(tmp.name)


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: _ui_tap.py <text>")
    needle = sys.argv[1].lower()

    tree = None
    for attempt in range(3):
        tree = dump_tree()
        for node in tree.iter("node"):
            text = node.get("text", "")
            desc = node.get("content-desc", "")
            if needle in text.lower() or needle in desc.lower():
                bounds = node.get("bounds", "")
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
                if not m:
                    continue
                x1, y1, x2, y2 = map(int, m.groups())
                cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                adb("shell", "input", "tap", str(cx), str(cy))
                return
        time.sleep(1)

    labels = set()
    for node in tree.iter("node"):
        for attr in ("text", "content-desc"):
            val = node.get(attr, "").strip()
            if val:
                labels.add(val)

    print(f"No UI element found matching '{sys.argv[1]}'.", file=sys.stderr)
    print("Visible text/content-desc on screen:", file=sys.stderr)
    for label in sorted(labels):
        print(f"  - {label!r}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
