#!/usr/bin/env python3
"""Write version.json: the catalogue the app's Settings > Check for updates reads.

The app fetches this file over HTTPS, compares `versionCode` with its own, and offers the APK at
`apkUrl` with the changelog in `notes`. Run it when a release is published, then commit the file:

    python3 scripts/release/version-json.py \
        --version 0.0.9-beta \
        --version-code 901 \
        --apk build/capcraft-android-0.0.9-beta-aosp.apk \
        --url https://github.com/OWNER/REPO/releases/download/TAG/capcraft-android-0.0.9-beta-aosp.apk \
        --note "Timeline lebih rapat" \
        --note "Thumbnail langsung muncul"

The APK's size and SHA-256 are measured here, so the app can check what it downloaded.
"""

import argparse
import hashlib
import json
import os
import sys
from datetime import date


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True, help="the version, as gradle.properties names it")
    parser.add_argument(
        "--version-code",
        type=int,
        required=True,
        help="the base code from gradle.properties (capcraft.versionCode); the build adds the ABI digit itself",
    )
    parser.add_argument("--apk", required=True, help="the signed APK the app will download")
    parser.add_argument("--url", required=True, help="where that APK is published (an https URL)")
    parser.add_argument("--note", action="append", default=[], help="one changelog line; repeat it")
    parser.add_argument("--released", default=date.today().isoformat(), help="the release date (default today)")
    parser.add_argument("--out", default="version.json", help="the file to write (default ./version.json)")
    args = parser.parse_args(argv)

    if not os.path.isfile(args.apk):
        print(f"{args.apk} is not a file", file=sys.stderr)
        return 1
    if not args.url.startswith("https://"):
        print("--url must be https", file=sys.stderr)
        return 1

    entry = {
        "version": args.version,
        "versionCode": args.version_code,
        "apkUrl": args.url,
        "sizeBytes": os.path.getsize(args.apk),
        "sha256": sha256(args.apk),
        "releasedAt": args.released,
        "notes": [note for note in args.note if note.strip()],
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(entry, handle, indent=2, ensure_ascii=False)
        handle.write("\n")
    print(f"wrote {args.out}: {entry['version']} ({entry['sizeBytes']} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())