# Changelog

Notable changes to xsofy. Each version is a `v*` git tag that releases both the
web build (Pages) and the native build (Homebrew). Entries are the net change a
player sees from the previous release; intra-cycle churn (introduced and fixed
between tags) is omitted. Web-only items are marked `(web)`; everything else
applies to both builds.

## v0.0.2

- Runes render in the browser terminal — v0.0.1 showed tofu/overflow, because the
  web build shipped let-go's generic shell. It now ships xsofy's own shell with
  the Fairfax HD rune face (#102). `(web)`
- New `?font=system` — render the terminal in the platform monospace font instead
  of the bundled rune face (#96). `(web)`
- Reproducible, shareable runs: `?seed=<n>` reproduces a run, `?replay=<code>`
  plays back a recording, and the seed is shown in-game (#62, #66, #69). `(web)`
- Balance pass: tamed slimes, throttled ranged fire rate, softened spider webs
  (#71).
- `Ctrl-C` as a confirm-quit alias for `Esc` (#76).
- Death screen: dropped a redundant death line and cleaned up the scroll residue
  in the message log (#109).

### Internal (not player-facing)

- Pages deploys from `v*` tags, gated by a browser smoke + seeded-determinism e2e
  (#59, #60).
- Branch-based Pages publishing + per-PR previews (#106).
- Client-owned WASM shell; built against published let-go v1.11.1 (#78, #94).
- Dungeon map viewer and terrain workbench dev tools (#88, #93).

[v0.0.2]: https://github.com/nooga/xsofy/releases/tag/v0.0.2
