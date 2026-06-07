#!/usr/bin/env python3
"""Run the let-go smoke driver and assert on its captured frame stream.
Catches: title frozen (T0 == T50), render throwing (non-zero exit / missing
sentinel), empty game render."""
import subprocess, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LG = os.environ.get("LG", "lg")

def segments(out: bytes):
    # Each buffer is named by the marker that ENDS it: content printed before
    # @@T0@@ is segs["T0"], before @@T50@@ is segs["T50"], etc.
    segs, cur = {}, []
    for line in out.split(b"\n"):
        if line.startswith(b"@@") and line.endswith(b"@@"):
            segs[line.strip(b"@").decode()] = b"\n".join(cur)
            cur = []
        else:
            cur.append(line)
    return segs

def main():
    p = subprocess.run([LG, "xsofy/test/smoke.lg"], cwd=ROOT,
                       capture_output=True)
    if p.returncode != 0:
        print("FAIL: smoke driver exited", p.returncode)
        print(p.stderr.decode(errors="replace")[:2000]); sys.exit(1)
    out = p.stdout
    if b"@@SMOKE-OK@@" not in out:
        print("FAIL: missing @@SMOKE-OK@@ sentinel (a render path threw)"); sys.exit(1)
    segs = segments(out)
    t0, t50 = segs.get("T0", b""), segs.get("T50", b"")  # frame 0, frame 50
    if not t0.strip() or not t50.strip():
        print("FAIL: a title frame was empty"); sys.exit(1)
    if t0 == t50:
        print("FAIL: title is frozen — frame 0 == frame 50"); sys.exit(1)
    if not segs.get("GAME", b"").strip():
        print("FAIL: game render produced no output"); sys.exit(1)
    print("OK: title animates; game renders; no render threw")

if __name__ == "__main__":
    main()
