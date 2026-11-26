#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Keep only the newest APKs of each series in the dist directory.

  CodexWeb-v<N>.apk   -> newest by version number
  Lumetro-*.apk       -> newest by mtime

Unversioned CodexWeb.apk / .idsig are build intermediates and are left alone.
"""
import argparse
import os
import re
import sys

CODEX_RE = re.compile(r"^CodexWeb-v(\d+)\.apk$")
LUMETRO_RE = re.compile(r"^Lumetro-.*\.apk$")


def prune(dist, pattern, keep, key_func, label):
    items = []
    for name in os.listdir(dist):
        m = pattern.match(name)
        if not m:
            continue
        path = os.path.join(dist, name)
        if not os.path.isfile(path):
            continue
        items.append((key_func(path, m), name, path))
    items.sort(key=lambda x: x[0], reverse=True)
    for _, name, path in items[keep:]:
        os.remove(path)
        print("[cleanup] removed old %s: %s" % (label, name))
    print("[cleanup] %s kept %d/%d" % (label, min(keep, len(items)), len(items)))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("dist", nargs="?", default=None)
    p.add_argument("--keep-codex", type=int, default=1)
    p.add_argument("--keep-lumetro", type=int, default=1)
    a = p.parse_args()
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    dist = os.path.abspath(a.dist or os.path.join(root, "build", "dist"))
    if not os.path.isdir(dist):
        print("[cleanup] no dist directory: %s" % dist, file=sys.stderr)
        return 0
    prune(dist, CODEX_RE, a.keep_codex,
          lambda path, m: int(m.group(1)), "CodexWeb")
    prune(dist, LUMETRO_RE, a.keep_lumetro,
          lambda path, m: os.path.getmtime(path), "Lumetro")
    return 0


if __name__ == "__main__":
    sys.exit(main())
