#!/usr/bin/env python3
"""Claude Code PostToolUse hook — Standard Clojure Style *check* (never modifies).

After an Edit/Write/MultiEdit, if the touched file is Clojure (.clj/.cljs/.cljc)
or let-go (.lg), run `standard-clj check` and, when it is not formatted, surface
a non-blocking note to Claude suggesting the matching `fix` command.

let-go `.lg` is treated as Clojure via standard-clj's `--file-ext lg` flag — the
`.standard-clj.edn` config has no key to register an extension, so the flag is
the only mechanism (it also makes a bare recursive run pick up .lg).

Contract: reads the hook payload as JSON on stdin; always exits 0 (warn only);
emits PostToolUse `additionalContext` when the file needs formatting.
"""
import json
import os
import shutil
import subprocess
import sys

CLOJURE_EXTS = (".clj", ".cljs", ".cljc", ".lg")


def find_standard_clj():
    return shutil.which("standard-clj") or (
        os.path.expanduser("~/.bun/bin/standard-clj")
        if os.path.exists(os.path.expanduser("~/.bun/bin/standard-clj"))
        else None
    )


def main():
    try:
        payload = json.load(sys.stdin)
    except Exception:
        return 0  # malformed payload → stay out of the way

    file_path = (payload.get("tool_input") or {}).get("file_path")
    if not file_path or not file_path.endswith(CLOJURE_EXTS) or not os.path.isfile(file_path):
        return 0

    exe = find_standard_clj()
    if not exe:
        return 0  # formatter not installed → no-op

    try:
        result = subprocess.run(
            [exe, "check", "--file-ext", "lg", "--log-level", "quiet", file_path],
            capture_output=True, text=True, timeout=20,
        )
    except Exception:
        return 0

    if result.returncode != 0:
        rel = os.path.relpath(file_path)
        note = (
            f"Standard Clojure Style: `{rel}` is not formatted (or failed to parse). "
            f"To fix: `standard-clj fix --file-ext lg \"{rel}\"`."
        )
        print(json.dumps({
            "hookSpecificOutput": {
                "hookEventName": "PostToolUse",
                "additionalContext": note,
            }
        }))
    return 0


if __name__ == "__main__":
    sys.exit(main())
