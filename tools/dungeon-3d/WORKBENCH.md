# Terrain-gen workbench

A streamlined `change generator code → regenerate → see + measure` loop for
dungeon-algorithm work, built on the dungeon-3d viewer. The point: tighten the
inner loop the substrate/connector proposals need to evaluate "did this actually
improve connectivity / feel / cost."

## Why a local lg server (not the wasm path)

`terrain.lg` is *interpreted*, so the dev loop doesn't need a Go/wasm rebuild —
only edits to let-go's own Go code do. A native lg HTTP server wrapping
`generate-level` beats both today's flow and the in-browser wasm version for dev:

- **No build step for algorithm edits** — edit `terrain.lg`, restart the server
  (sub-second) or redefine in a live REPL; no `just build`, no wasm bundle.
- **No cross-origin isolation, no SharedArrayBuffer, no wasm payload** — the page
  just `fetch()`es from localhost. The whole COI headache that gates the wasm
  path disappears for local dev.
- **Native-speed generation** + full seed/param control via query args.

The wasm version (`dungeon-3d-live-wasm-interop.md`) still earns its place for a
*shareable/hosted* demo (githack, no local server) — i.e. the upstream-PR
audience. For the dev workbench, the local server wins.

## Architecture

```
lg [-n] tools/dungeon-3d/terrain-server.lg [port]
   │  http/serve (Ring-style)            nREPL (-n, :2137)
   ├─ GET /floors?seed=42&depths=8  →  {w,h,seed,floors:[{depth,grid,down,up,metrics}]}
   └─ GET /                         →  workbench.html
                  ▲                                    │
        workbench.html (reuses the viewer's canvas/CSS rendering)
        seed/depth controls → fetch → render stack + metrics panel
```

## The spicy bit: live-coding the generator (nREPL)

`lg -n tools/dungeon-3d/terrain-server.lg` runs the HTTP server **and** an nREPL
(:2137) in one process. Connect a REPL, redefine a generator fn (or a whole phase
of `generate-level`) in the live image, then refetch in the workbench — the new
algorithm renders immediately, **no restart, no rebuild**. Live-coding the dungeon
generator with the 3D viewer + metrics updating in real time.

## Metrics (per floor)

Surfaced alongside each generated floor (tile counts already render in the viewer):

- **components** — distinct reachable floor regions (flood over `floor-tiles`).
  `1` = the connectivity invariant holds; `>1` means a change broke it. The
  cheapest regression check for connectivity work.
- **dead-ends** — floor tiles with exactly one floor neighbour. Layout-density /
  "feels designed vs. maze" signal.
- **rooms** — room-center count from `generate-level`.
- **gen-ms** — per-floor `generate-level` wall time (`System/nanoTime`). Algorithm
  changes show their perf cost immediately; ties into the perf-grid harness.
  (Currently ~80–125ms/floor — the CA blobs + lakes dominate.)
- **loops** — *coarse for now.* Computed as cyclomatic `E − V + C` over the raw
  tile graph, so open rooms (dense 4-connected meshes) inflate it to the hundreds.
  **Refinement:** compute over a room/junction graph (or count extra-connectors)
  to get the actual "designed loop" signal the connector proposal wants. v1 ships
  the raw number; treat it as a placeholder.

## Renderer

Reuses the dungeon-3d viewer's **canvas/CSS 3D rendering** — clean, quick, and the
multi-floor overview is ideal for algo work (every floor + its metrics at once).
*Future alt:* an xterm renderer for the in-game terminal feel (lg's own wasm shell
already uses xterm, so it's cheap to add) — noted, not built.

## Scope

- **v1 (this branch):** `terrain-server.lg` (done + tested) + `workbench.html`
  (next) + the metrics above. Controls: seed + floor count only — both are already
  `generate-level` args, zero `terrain.lg` change.
- **Later:** deeper gen-param sliders (cave density, water…) need a `generate-level`
  overrides hook (a `terrain.lg` change, defaults preserve game behavior); A/B
  (same seed under two code versions, diff metrics + tiles); the loops refinement.

## Run

```
# from the xsofy root
lg tools/dungeon-3d/terrain-server.lg            # serve on :7070
lg tools/dungeon-3d/terrain-server.lg 8080       # custom port
lg -n tools/dungeon-3d/terrain-server.lg         # + nREPL :2137 (live-coding)
```

Tracks let-go `main` (native xxh3 + `*command-line-args*` + `http`/`io`/`json`/
`System`), same as the rest of the tool.
