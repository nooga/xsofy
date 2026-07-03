# GL Frontend Experiment: Glyph-Quad Renderer, Screen-Fall Transition, First-Person Mode

**Date:** 2026-07-03
**Status:** Approved design, pre-implementation
**Branch:** `gl-frontend` (xsofy) + a companion branch in `let-go`
**Scope:** Experiment branch only — no obligation to preserve terminal/WASM
frontend parity on this branch. Integration is a later, separate decision.

## Goal

Render xsofy in a hardware-accelerated window that runs **standalone
(native macOS/Linux/Windows)** and **in the browser (WASM/WebGL)** from the
same code. Level 1 plays top-down as today, drawn as glyph-textured quads.
On entering level 2, the playfield visibly **tips over into the floor** of a
3D world and the game continues in **first-person mode**.

Raylib was considered and rejected: raylib-go is cgo-based, and cgo does not
exist under `GOOS=js/wasm`, so it can never satisfy the browser requirement.

## Architecture

Three layers, with as little Go as possible. let-go's compiler lowers `.lg`
code to Go, so performance-sensitive engine code can stay in Lisp and be
AOT-compiled when needed.

```
┌───────────────────────────────────────────────┐
│ xsofy game logic (.lg) — UNCHANGED            │
│ world, entities, combat, determinism, replay  │
├───────────────────────────────────────────────┤
│ GL engine (.lg, AOT-lowerable)                │
│ retained scene graph, glyph atlas layout,     │
│ camera/projection math, fall morph, FP view   │
├───────────────────────────────────────────────┤
│ glplat (Go, ~12 functions) — in let-go repo   │
│ window/canvas + GL context, texture upload,   │
│ triangle-batch submit, input events, present  │
│   native: GLFW + OpenGL (go-gl, cgo)          │
│   browser: WebGL2 via syscall/js (build tag)  │
└───────────────────────────────────────────────┘
```

### glplat (Go platform layer, lives in let-go)

A hand-written Go package with a **pure-Go public API**; cgo appears only in
the build-tagged native backend. Because the API surface is plain Go,
`lginterop -smart` can generate the let-go bindings without hitting the
source importer's cgo limitation. The generated wrappers compile into a
custom `lg` binary (and into the wasm build).

Target API (names indicative):

| fn | purpose |
|---|---|
| `Init(w, h, title)` | open window / bind canvas, create GL context |
| `ShouldClose() bool` | window close requested |
| `PollEvents() []Event` | keyboard (and later mouse) events since last poll |
| `LoadTexture(rgba, w, h) id` | upload the font atlas |
| `BeginFrame(r, g, b)` | clear |
| `SubmitTriangles(tex, verts)` | one interleaved vertex batch: pos(x,y,z) uv rgba |
| `SetMatrix(m4)` | current MVP uniform |
| `EndFrame()` | swap / present |
| `Time() float64` | monotonic seconds for animation |
| `FrameLoop(cb)` | drive the loop (native `for`; browser `requestAnimationFrame`) |

One shader pair, fixed: textured, vertex-colored triangles with a single MVP
matrix. Everything visual is built from that primitive by lg code.

### GL engine (new `.lg` namespaces in xsofy)

- `xsofy.gl.atlas` — bake a **half-width** monospace font atlas (cell aspect
  preserved — the rune effects assume half-width cells); glyph → UV lookup.
- `xsofy.gl.scene` — retained scene graph as lg data: nodes carry transform +
  quad lists; dirty-tracked rebuild into vertex batches.
- `xsofy.gl.camera` — mat4 math (ortho for top-down, perspective for FP),
  written in lg, AOT-lowered if profiling demands.
- `xsofy.gl.grid` — adapter: consume the glyph grid xsofy's renderer already
  computes and emit one textured quad per cell.
- `xsofy.gl.fall` — the level-2 transition (below).
- `xsofy.gl.fpview` — first-person renderer (below).
- `main_gl.lg` — entry point: init, frame loop, input → existing dispatch.

### Frame loop vs. turn-based logic

The platform drives frames (60fps); game logic stays turn-based and
deterministic. Each frame: poll input → feed discrete actions into the
existing dispatch exactly as the terminal loop does → advance animations
(camera tweens, fall morph) by wall-clock time → rebuild dirty scene nodes →
draw. **Animation time never feeds game logic**, so determinism, replay, and
save are untouched.

## Rendering design

### Top-down (level 1)

Hybrid "glyphs as textures": every cell of the existing render grid becomes
a textured quad (glyph from the atlas, fg/bg colors as vertex colors).
Visually faithful to the terminal game, but each cell is an independent
drawable that can rotate, fall, and be lit — which is what makes the
transition possible.

### The fall transition (entering level 2)

**Playfield tips into the floor.** One parametric morph `t: 0→1`:

1. The top-down glyph plane rotates about a horizontal axis near the
   player, tilting away from the camera like a tabletop being lowered,
   until it lies flat — it *is* the floor of the 3D world.
2. As it tilts, wall cells extrude upward: each wall glyph grows from a
   flat quad into a glyph-textured box (walls at world height 1).
3. The camera simultaneously descends and pulls in behind the player
   glyph, landing at eye level facing the player's current heading.

The same scene graph renders throughout — no scene swap, one continuous
camera + geometry interpolation.

### First-person mode (level 2+)

- **Geometry:** floor and ceiling as glyph-textured planes; walls as
  extruded boxes textured with their map glyph (a `#` wall is a wall of
  `#`). Monsters, items, and runes are **billboarded glyph quads** that
  always face the camera.
- **Movement:** grid-step, turn-based, dungeon-crawler style (Eye of the
  Beholder): 90° turns and one-tile steps, each a discrete game action.
  The camera *animates* smoothly between poses, but the game state only
  ever sees the same discrete actions it sees today.
- **Facing** becomes part of the FP presentation layer; if game rules need
  it (e.g. what "forward" means), it maps onto the existing 4-directional
  move actions.
- FOV/lighting reuse the game's computed visibility: cells outside FOV are
  dark or omitted, preserving the roguelike information game.

## Repo split

- **let-go branch:** `pkg/glplat` (or equivalent), its two backends, an
  example proving `lginterop -smart` generates working bindings, and any
  lginterop/gogen fixes shaken out by the spike.
- **xsofy branch `gl-frontend`:** everything else (engine `.lg` code,
  `main_gl.lg`, atlas asset/tooling, this spec).

## Risks and spikes (in order)

1. **Spike 0 — binding proof:** minimal glplat (`Init`, clear color,
   `EndFrame`) through lginterop into a custom `lg`; a 10-line `.lg`
   opens a window natively. This validates the whole toolchain before any
   engine code exists.
2. **Spike 1 — browser backend:** same `.lg` runs under the wasm build with
   the WebGL backend. Main unknowns: the existing wasm build runs the VM in
   a worker (OffscreenCanvas or main-thread relocation may be needed), and
   `FrameLoop` inversion under rAF.
3. **Perf:** scene rebuild + mat4 math in interpreted lg may be slow; the
   designed mitigation is AOT-lowering those namespaces, which is exactly
   the codepath the project wants exercised anyway.
4. **Atlas fidelity:** the half-width cell aspect must match the terminal
   rendering or rune effects will look wrong.

## Testing

- glplat: Go unit tests for event queue + vertex batch layout; build-tag
  compile checks for both backends in CI.
- lg engine: pure-function tests for mat4/camera math and the fall morph
  parametrization (deterministic, no GL needed).
- Scene grid adapter: golden tests — given a known render grid, assert quad
  count, UVs, and colors.
- Manual/visual: a demo scene per milestone (top-down level 1, fall
  transition triggered by key, FP walkabout).
- Game-logic determinism: the existing replay/save test suite must stay
  green — the GL frontend never touches game state semantics.

## Non-goals (this branch)

- Terminal/WASM-xterm frontend parity or removal — untouched decision.
- Sprite/tile art, smooth real-time movement, mouselook.
- Sound changes, gameplay changes beyond the FP presentation of levels 2+.
