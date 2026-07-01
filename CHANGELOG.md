# Changelog

Notable changes to xsofy. Each version is a `v*` git tag that deploys the web build.

## v0.0.2

### Highlights

- **Runes render in the web build.** The browser terminal now ships the Fairfax HD rune face, so Runic-block glyphs display instead of tofu/overflow.
- **`?font=system`** — a URL option to render the terminal in the platform monospace font instead of the bundled rune face.
- **Cleaner death screen** — the raw replay code no longer overlays the tombstone.

### Added

- `?font=system` to render the terminal in the platform mono (#96)
- Knot rewind cords (#74)
- Per-turn ring container triggers (#75)
- `Ctrl-C` as a confirm-quit alias for `Esc` (#76)

### Fixed

- Death screen no longer overlays the on-screen replay code (#101)
- Web-font measurement race that twitched the terminal (#95)
- WASM-safe title input poll (a deploy regression) (#61)
- Dev replay dump now routes off-terminal on web (#104)

### Changed

- The web build ships xsofy's own client shell (rune font + `?font=system`) instead of let-go's generic shell (#78, #102)
- Balance: tamed slimes, throttled ranged fire rate, softened spider webs (#71)
- Built against published let-go v1.11.0/v1.11.1; the `?seed=` / `?replay=` URL bridge is now native (#94)

### Infrastructure

- Pages deploys from `v*` tags, gated by a browser smoke + seeded-determinism e2e (#59, #60)
- Branch-based Pages publishing + per-PR previews (#106)
- Dungeon map viewer and terrain workbench dev tools (#88, #93)

[v0.0.2]: https://github.com/nooga/xsofy/releases/tag/v0.0.2
