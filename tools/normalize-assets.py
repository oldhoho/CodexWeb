#!/usr/bin/env python3
"""Rewrite aapt's APK so the runtime payload is stored under its real names.

aapt ignores `-0` for files without a known extension and drops ".gz" from
asset names, so the packaged asset list is not what the app expects. This
script copies every entry to a fresh archive, re-adding payload files under
their real names and forcing compression=NONE for them.

usage: normalize-assets.py <project-root> <in.apk> <out.apk>
"""
import os
import sys
import zipfile

root, src, dst = sys.argv[1], sys.argv[2], sys.argv[3]
assets = os.path.join(root, "assets")

with zipfile.ZipFile(src) as zin:
    names = {n for n in zin.namelist()}
    payload_entries = sorted(
        os.path.relpath(os.path.join(b, f), assets).replace(os.sep, "/")
        for b, _, fs in os.walk(os.path.join(assets, "payload"))
        for f in fs
    )
    with zipfile.ZipFile(dst, "w", zipfile.ZIP_STORED, allowZip64=True) as zout:
        written = set()
        for info in zin.infolist():
            name = info.filename
            if name.startswith("assets/payload/"):
                continue
            data = zin.read(name)
            out = zipfile.ZipInfo(name, info.date_time)
            out.compress_type = (zipfile.ZIP_STORED if info.compress_type == 0
                                 else zipfile.ZIP_DEFLATED)
            out.external_attr = info.external_attr
            zout.writestr(out, data)
            written.add(name)
        for rel in payload_entries:
            arc = "assets/" + rel
            if arc in written:
                continue
            with open(os.path.join(assets, rel), "rb") as f:
                data = f.read()
            info = zipfile.ZipInfo(arc, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = (0o100644 << 16)
            zout.writestr(info, data)
            written.add(arc)

dropped = [n for n in names if n.startswith("assets/payload/") and n not in written]
print("normalize-assets: %d payload entries stored, %d aapt variants dropped"
      % (len(payload_entries), len(dropped)))
