#!/usr/bin/env python3
"""Tap the center of the first on-screen element whose text or
content-description contains the given string (case-insensitive),
via `adb shell uiautomator dump` + `adb shell input tap`.

Usage: _ui_tap.py <text>
"""
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


def adb(*args):
    subprocess.run(["adb", *args], check=True, capture_output=True)


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: _ui_tap.py <text>")
    needle = sys.argv[1].lower()

    adb("shell", "uiautomator", "dump", "/sdcard/window_dump.xml")
    with tempfile.NamedTemporaryFile(suffix=".xml") as tmp:
        adb("pull", "/sdcard/window_dump.xml", tmp.name)
        tree = ET.parse(tmp.name)

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

    sys.exit(f"No UI element found matching '{sys.argv[1]}'")


if __name__ == "__main__":
    main()
