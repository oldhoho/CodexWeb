#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Keep builds lean: prune old APKs and reproducible intermediates.

  dist/CodexWeb-v<N>.apk -> newest by version number
  dist/Lumetro-*.apk     -> newest by mtime
  build/android/*.old.<pid>  -> build rename-aside residue (delete all)
  build/android/app.{unsigned,raw,dexed,aligned}.apk -> regenerated every build

The signing keystore (build/codexweb.jks) and the deliverables in dist/ are
never touched. Unversioned CodexWeb.apk/.idsig in dist/ are the current build
output and are kept.
"""
import argparse
import os
import re
import shutil
import sys

CODEX_RE = re.compile(r"^CodexWeb-v(\d+)\.apk$")
LUMETRO_RE = re.compile(r"^Lumetro-.*\.apk$")
OLD_DIR_RE = re.compile(r"^(gen|classes|dex)\.old\.\d+$")
INTERMEDIATE_APKS = (
    "app.unsigned.apk", "app.raw.apk", "app.dexed.apk", "app.aligned.apk",
)


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


def prune_build_intermediates(root):
    out = os.path.join(root, "build", "android")
    if not os.path.isdir(out):
        return 0
    freed = 0
    removed = 0
    for name in os.listdir(out):
        path = os.path.join(out, name)
        is_old_dir = OLD_DIR_RE.match(name) and os.path.isdir(path)
        is_intermediate = name in INTERMEDIATE_APKS and os.path.isfile(path)
        if not (is_old_dir or is_intermediate):
            continue
        try:
            size = 0
            if is_intermediate:
                size = os.path.getsize(path)
            else:
                for dp, _, files in os.walk(path):
                    for f in files:
                        try:
                            size += os.path.getsize(os.path.join(dp, f))
                        except OSError:
                            pass
            if is_old_dir:
                shutil.rmtree(path)
            else:
                os.remove(path)
            freed += size
            removed += 1
            print("[cleanup] removed intermediate: %s" % name)
        except OSError as e:
            # A stale javac/java handle can keep a file locked; skip it and
            # let the next build's rename-aside strategy deal with it.
            print("[cleanup] skip %s: %s" % (name, e), file=sys.stderr)
    print("[cleanup] build intermediates removed %d, freed ~%.0f MB"
          % (removed, freed / (1024 * 1024)))
    return removed


def main():
    p = argparse.ArgumentParser()
    p.add_argument("dist", nargs="?", default=None)
    p.add_argument("--keep-codex", type=int, default=1)
    p.add_argument("--keep-lumetro", type=int, default=1)
    p.add_argument("--keep-intermediates", action="store_true",
                   help="do not prune build/android intermediates")
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
    if not a.keep_intermediates:
        prune_build_intermediates(root)
    return 0


if __name__ == "__main__":
    sys.exit(main())
