# Terrain-gen workbench

A `change generator code → regenerate → see + measure` loop for dungeon-algorithm
work, built on the dungeon-3d viewer. Tightens the inner loop the substrate/connector
proposals need to evaluate "did this actually improve connectivity / feel / cost."

## What's built (this branch)

A local lg HTTP server + a browser page, with in-browser live-coding:

```
lg [-n] tools/dungeon-3d/terrain-server.lg [port]    # binds 127.0.0.1 (/eval runs code)
   ├─ GET  /floors?seed=42&depths=8  → {w,h,seed,floors:[{depth,grid,down,up,metrics}]}
   ├─ POST /eval   (body = lg code)  → evals in xsofy.terrain → {ok,value|error}
   └─ GET  /                         → workbench.html
                  ▲                                    │
   workbench.html (reuses the viewer's canvas/CSS rendering)
   seed/depth controls (auto-regen on step) → fetch → render stack + metrics panel
   bottom live-coding console: eval into the running image + quick-example buttons
```

## Why a local lg server (for dev)

`terrain.lg` is *interpreted*, so the dev loop needs no Go/wasm rebuild — only edits
to let-go's own Go code do. The native server beats the wasm path for *local* dev:
no build step (edit `.lg`, restart or live-redefine), no wasm payload, native-speed
generation, full seed control via query args. The wasm path is the *hosted/shareable*
sibling — see "Modes" below.

## Live-coding

Two ways to redefine generator fns in the running image; vars are late-bound, so the
next `/floors` call uses the new code — no restart, no rebuild.

- **In-browser eval console (primary).** The bottom console POSTs to `/eval`, which
  evals in `xsofy.terrain`, shows the result, and auto-regenerates. Quick-example
  buttons demonstrate it: **kill grass** (`place-grass-patch!` no-op), **kill lakes**
  (`place-lake!` no-op), **add fire** (`carve-room!` carves fire tiles — walkable and
  counts as floor, so connectivity holds). ⌘/Ctrl+Enter evals.
- **Editor nREPL.** `lg -n` runs an nREPL on :2137 alongside the server; let-go's
  nREPL works with CIDER/Calva/Conjure (writes `.nrepl-port`, so Conjure
  auto-connects). Eval forms from your `terrain.lg` buffer; refetch to see them.

### The VM model (important)

The server holds the let-go *image*; the page is just a view. **Reloading the browser
re-fetches from the same image, so redefs persist.** To reset everything, **restart
the server** (fresh process = original source). let-go exposes no clean in-session
namespace reload (`:reload` isn't a require flag; `load-file` doesn't resolve in the
eval context), so restart is the reset today. (A hosted-wasm workbench would reset on
reload instead — see Modes.)

## Metrics (per floor)

Surfaced alongside each generated floor (tile counts already render in the viewer):

- **components** — distinct reachable floor regions (flood over `floor-tiles`).
  `1` = the connectivity invariant holds; `>1` means a change broke it. The cheapest
  regression check for connectivity work.
- **dead-ends** — floor tiles with exactly one floor neighbour. Layout-density /
  "feels designed vs. maze" signal.
- **rooms** — room-center count from `generate-level`.
- **gen-ms** — per-floor `generate-level` wall time (`System/nanoTime`). Algorithm
  changes show their perf cost immediately; ties into the perf-grid harness.
- **loops** — *coarse for now.* Cyclomatic `E − V + C` over the raw tile graph, so
  open rooms (dense 4-connected meshes) inflate it to the hundreds. **Refinement:**
  compute over a room/junction graph (or count extra-connectors) for the real
  "designed loop" signal the connector proposal wants. Treat it as a placeholder.

## Modes: server + hosted-wasm

Both modes share the UI and the `dungeon-build.lg` floors+metrics builder; only a thin
Backend adapter differs: `ServerBackend` (fetch to `terrain-server.lg`) vs `WasmBackend`
(in-page `LetGoHost.eval`).

- **Server (built):** persistent image, fastest local iteration, reset = restart.
- **Hosted wasm (built, validated):** the let-go image runs *in the page*, so reload =
  fresh VM (reset for free) and the workbench can host statically (no local server) for
  shareable demos. `WasmBackend` drives it through the public `LetGoHost.eval(code)` API;
  `generate` and `eval-str` return their payloads out of band via `js/emit` (the
  `xsofy/floors` and `xsofy/eval-result` events), since `LetGoHost.eval` only hands back
  a stringified value. Validated end-to-end in a browser: floors render in-page and the
  console redefines into the live `xsofy.terrain` image, no server.
  - **let-go requirement:** wasm mode needs a let-go built with **`-w-host-eval`**
    (shipped in the v1.11.0 release). Server mode needs only let-go `main`.
  - **COI:** a generator-only wasm never calls `read-key`, so it can run on the main
    thread without cross-origin isolation (the static-hosting path). `LetGoHost.eval` is
    dual-mode (main-thread direct or worker relay under COI), so both work; validated so
    far under COI (worker mode).

## Gotchas

- `/eval` runs arbitrary code, so it's hardened against cross-origin abuse: the
  server binds `127.0.0.1` only, sends no `Access-Control-Allow-Origin`, and rejects
  any request whose `Origin` isn't the server's own (a page on another origin can't
  POST code to it). Requests with no `Origin` (curl, local tooling) still pass.
- `[http :refer :all]` refers `http/get`, which shadows core `get` — use `[http :as
  http]`. (Bit us with a flaky `/floors` 500.)
- `loops` is coarse (see Metrics).

## Run

```
# from the xsofy root (or the worktree at the workbench branch)
lg tools/dungeon-3d/terrain-server.lg            # serve on :7070, open http://localhost:7070
lg tools/dungeon-3d/terrain-server.lg 8080       # custom port
lg -n tools/dungeon-3d/terrain-server.lg         # + nREPL :2137 (editor live-coding)
```

Open the page, set/step seed & depths (auto-regenerates), and expand the **LIVE-CODING**
console to eval into the running image. Tracks let-go `main` (native xxh3 +
`*command-line-args*` + `http`/`io`/`json`/`System`), same as the rest of the tool.

### Hosted-wasm mode (no server)

Needs a let-go with `-w-host-eval` (v1.11.0+). Build the image, inject this page into
the host-core bundle, and serve with cross-origin-isolation headers:

```
lg -w dist -w-shell none -w-host-eval -w-wasm external tools/dungeon-3d/wasm-entry.lg
# inject workbench.html into dist/index.html, then serve dist/ with COOP/COEP headers
# and open  …/index.html?mode=wasm
```

`?mode=wasm` swaps `WasmBackend` in: `generate`/`eval-str` run in the in-page image and
return via `js/emit`. Reset = reload the page.
