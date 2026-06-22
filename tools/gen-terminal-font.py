#!/usr/bin/env python3
"""Regenerate the inlined terminal font in tools/xsofy-shell.html.

xsofy renders into xterm with a single face (Fairfax HD) so every glyph —
ASCII, box-drawing, runes, symbols — shares one advance and the grid can't
drift. Fairfax HD is a *halfwidth* monospace: narrower cells than a normal
mono, which is exactly why mixing it with a fallback mono (the old
unicode-range approach) drifted. One face, one advance, no drift.

This derives the subset from what the game actually renders, so it can't go
stale: it scans xsofy/*.lg for every non-ASCII codepoint, adds all printable
ASCII and the full Runic block (runes may be selected at runtime, not just the
literals in source), subsets Fairfax HD to exactly that, and rewrites the
@font-face base64 in the shell in place.

Run via the Makefile (`make rune-font`) or directly:
    uv run --with fonttools --with brotli python3 tools/gen-terminal-font.py

Python (not let-go like the other tools/) because font subsetting needs
fonttools/pyftsubset — there's no let-go path for it.

The source TTF (3.2 MB) is NOT vendored; it's downloaded once from a pinned
open-relay commit, checksum-verified, and cached under tools/fonts/.cache/
(gitignored). The committed artifact is the base64 in the shell, not the TTF.
"""
import base64, hashlib, pathlib, re, subprocess, sys, tempfile, urllib.request

# Pinned source: kreativekorp/open-relay @ this commit. Bump the pin + hash
# together to take a font update (and re-run; review the shell diff).
PIN = "12f11d0cb969c2a03feb48fd04a6e59a262aa5d9"
TTF_URL = f"https://raw.githubusercontent.com/kreativekorp/open-relay/{PIN}/FairfaxHD/FairfaxHD.ttf"
TTF_SHA256 = "4dde4e244cc525e89c61ef9c62a633e8bb4d5387d36df85374d89c709ec9ecdd"

ROOT = pathlib.Path(__file__).resolve().parent.parent
CACHE = ROOT / "tools" / "fonts" / ".cache" / "FairfaxHD.ttf"
SHELL = ROOT / "tools" / "xsofy-shell.html"
GAME_GLOB = "xsofy/**/*.lg"

# Always included regardless of what the scan finds: all printable ASCII (the
# terminal renders arbitrary text, not just literals in source) and the whole
# assigned Runic block (runes can be chosen at runtime).
ASCII = range(0x20, 0x7F)
RUNIC = range(0x16A0, 0x1700)


def fetch_ttf() -> pathlib.Path:
    if CACHE.exists() and hashlib.sha256(CACHE.read_bytes()).hexdigest() == TTF_SHA256:
        return CACHE
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading Fairfax HD TTF (pinned {PIN[:10]})…")
    data = urllib.request.urlopen(TTF_URL, timeout=120).read()
    got = hashlib.sha256(data).hexdigest()
    if got != TTF_SHA256:
        sys.exit(f"checksum mismatch: expected {TTF_SHA256}, got {got}")
    CACHE.write_bytes(data)
    return CACHE


def scan_codepoints() -> set[int]:
    cps: set[int] = set()
    for p in ROOT.glob(GAME_GLOB):
        for ch in p.read_text(errors="replace"):
            if ord(ch) > 0x7E and ord(ch) not in (0x09, 0x0A, 0x0D):
                cps.add(ord(ch))
    return cps


def main() -> None:
    ttf = fetch_ttf()
    used = scan_codepoints()
    unicodes = sorted(set(ASCII) | set(RUNIC) | used)

    with tempfile.NamedTemporaryFile(suffix=".woff2", delete=False) as tmp:
        out = pathlib.Path(tmp.name)
    subprocess.run(
        ["pyftsubset", str(ttf),
         "--unicodes=" + ",".join(f"U+{c:04X}" for c in unicodes),
         "--flavor=woff2", f"--output-file={out}"],
        check=True,
    )
    woff2 = out.read_bytes()
    out.unlink()

    # Verify every scanned glyph survived (a used codepoint Fairfax lacks would
    # silently fall back to mono and reintroduce the drift).
    from fontTools.ttLib import TTFont
    import io
    have = set(TTFont(io.BytesIO(woff2)).getBestCmap().keys())
    missing = sorted(c for c in used if c not in have)
    if missing:
        sys.exit("Fairfax HD lacks game glyphs: " + ", ".join(f"U+{c:04X}" for c in missing))

    b64 = base64.b64encode(woff2).decode()
    html = SHELL.read_text()
    html, n = re.subn(
        r"(src: url\(data:font/woff2;base64,)[A-Za-z0-9+/=]+(\) format\('woff2'\);)",
        lambda m: m.group(1) + b64 + m.group(2), html,
    )
    if n != 1:
        sys.exit(f"expected exactly one @font-face src in {SHELL}, found {n}")
    SHELL.write_text(html)

    n_glyphs = len(have)
    print(f"wrote {SHELL.relative_to(ROOT)}: {len(woff2):,} B woff2 "
          f"({len(b64):,} B base64), {n_glyphs} glyphs, "
          f"{len(used)} from source + ASCII + full Runic block")


if __name__ == "__main__":
    main()
