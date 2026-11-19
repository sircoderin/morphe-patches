#!/usr/bin/env python3
"""Add newly released app versions to the patches' supported versions.

For each tracked app, read its APKMirror release feed. If the newest stable
version is newer than every version listed in that app's Compatibility block
in Constants.kt, add it as a supported AppTarget. Morphe Manager then shows
"App update available" for the patched app once the next release is published.

Writes `changed=true|false` and `message=<commit subject>` to $GITHUB_OUTPUT.
Exits non-zero if a feed can't be read, so a broken check fails loudly.
"""
import os
import re
import sys
import urllib.request

CONSTANTS = os.environ.get(
    "CONSTANTS_PATH", "patches/src/main/kotlin/app/sircoderin/patches/shared/Constants.kt")

APPS = [
    {
        "name": "Twilight",
        "scope": "twilight",
        "constant": "COMPATIBILITY_TWILIGHT",
        "feed": "https://www.apkmirror.com/apk/urbandroid-team/twilight/feed/",
        "title_prefix": "Twilight: Blue light filter ",
    },
]

VERSION_RE = re.compile(r"^\d+(?:\.\d+)+$")


def vkey(v):
    return tuple(int(p) for p in v.split("."))


def latest_feed_version(app):
    req = urllib.request.Request(app["feed"], headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        xml = r.read().decode("utf-8", "replace")
    versions = []
    for title in re.findall(r"<title>([^<]*)</title>", xml):
        if not title.startswith(app["title_prefix"]):
            continue
        # "Twilight: Blue light filter 14.32 by Petr Nálevka (Urbandroid)"
        v = title[len(app["title_prefix"]):].split(" by ")[0].strip()
        if VERSION_RE.match(v):  # skips "14.33 beta", "14.33-alpha", etc.
            versions.append(v)
    if not versions:
        raise RuntimeError(f"{app['name']}: no stable versions found in {app['feed']}")
    return max(versions, key=vkey)


def main():
    src = open(CONSTANTS, encoding="utf-8").read()
    added = []
    for app in APPS:
        start = src.index(f"val {app['constant']} = Compatibility(")
        end = src.index("\n    )", start)
        block = src[start:end]
        listed = re.findall(r'AppTarget\(version = "([^"]+)"', block)
        latest = latest_feed_version(app)
        newest_listed = max(listed, key=vkey) if listed else None
        print(f"{app['name']}: feed {latest}, newest supported {newest_listed}")
        if newest_listed and vkey(latest) <= vkey(newest_listed):
            continue
        # Insert as the first entry of `targets = listOf(`, above any existing comments.
        anchor = re.search(r"targets = listOf\(\n(\s*)", block)
        if anchor is None:
            raise RuntimeError(f"{app['name']}: no `targets = listOf(` in {app['constant']}")
        indent = anchor.group(1)
        line = (f"// Added automatically when released on APKMirror (not hand-tested).\n"
                f'{indent}AppTarget(version = "{latest}"),\n{indent}')
        block = block[:anchor.end()] + line + block[anchor.end():]
        src = src[:start] + block + src[end:]
        added.append((app, latest))

    if added:
        open(CONSTANTS, "w", encoding="utf-8").write(src)
    msg = ""
    if added:
        scope = ",".join(a["scope"] for a, _ in added)
        msg = f"bump({scope}): support " + ", ".join(f"{a['name']} {v}" for a, v in added)
        print(msg)
    out = os.environ.get("GITHUB_OUTPUT")
    if out:
        with open(out, "a") as f:
            f.write(f"changed={'true' if added else 'false'}\n")
            f.write(f"message={msg}\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)
