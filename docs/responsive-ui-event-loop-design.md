# Responsive UI Event Loop (`xsofy.ui`) Design

**Date:** 2026-06-01
**Status:** Spec — design approved in brainstorming; ready for implementation plan
**Builds on:**
- Deterministic world / `xsofy.dispatch` (the `seed` + `:action-log` replay contract; pure-UI vs world-mutating action split).
- let-go `term/key-pending?` (non-blocking input peek, native + wasm) and the scoped supervisor (`with-scope`, `scope-open`/`scope-close!`/`scope-live`).
- Generalizes PR #51 (`fix/animated-screen-particles`), which fixed title/death particle animation locally with a goroutine + channel timer. Its `read-key-chan` / `make-key-poller` / `drive-until-key` helpers are superseded by this module.
- Origin: review comment by `mparrett` on PR #51 (issue comment 4593168067) surfacing the `key-pending?` polled-peek option.

## Elevator pitch

A small, composable, Rx-flavored event loop (`xsofy/ui.lg`) for terminal UIs. Screens describe **what sources they listen to** (keyboard, a frame clock, later: network/multi-timer) and **how state folds over events** (a pure `step`/scan), and the loop drives render. It is built on a **non-blocking poll** source contract so the exact same loop runs in a native terminal and in the browser (wasm) without freezing — and it keeps cosmetic animation **out** of the deterministic replay stream, so animated screens never corrupt `(seed, action-log)`.

## Goals

1. **Responsive idle animation.** Title, death, and the main play screen animate while waiting for input, in both native and browser builds.
2. **Determinism-safe.** Cosmetic frame ticks advance UI state only; they are never folded into the seed or recorded in `:action-log`. Replay of `:world` stays bit-identical regardless of frame timing.
3. **Composable / Rx semantics.** Sources merge; operators are transducers (`map`/`filter`) shaping each tick's event batch; `scan` is the state fold (`step`); loop termination (`take-until`) is `step` returning `(reduced result)` across iterations — not a per-batch transducer, since it must persist between ticks; `with-scope` is the subscription lifecycle.
4. **Portable.** One owning loop for native + wasm. The input path never blocks while idle (the only browser-safe option under single-threaded wasm).
5. **Upstream-ready.** `xsofy/ui.lg` depends only on let-go (channels, core transducers, the scope macro) — no xsofy-specific deps — so it can later lift into let-go itself.
6. **Backend-agnostic core.** `run-loop`/`step` depend only on the **source contract** (poll → events) and an **injected render/present** — never on `term` directly. The terminal (native + wasm) is *backend #1*; a future 2D graphical backend (including a Switch target — controller input source + framebuffer present) reuses the same loop and `step` with different sources and render. Building that backend is out of scope here; not foreclosing it is in scope.

## Non-goals

- A full push-based Observable runtime (schedulers, hot/cold, backpressure). Rejected as YAGNI for a terminal roguelike; operators are transducers instead.
- Multiplexing real network sources **now**. The source contract leaves the door open (`chan-source` adapter), but no network source is implemented in this work.
- Changing the deterministic simulation contract. `xsofy.dispatch`, `xsofy.det`, and replay semantics are untouched; this module sits *around* them.
- A JS/`requestAnimationFrame`-driven harness. let-go owns the loop in both builds (confirmed: `main.lg` already runs one owning `game-loop` with `*in-wasm*` branches).
- **A 2D graphical / Switch backend now.** A stated *eventual* goal is a proper 2D graphical UI (with a desire to run on Switch). This work ships the terminal backend only — but per Goal 6 the loop/`step`/source/render seam is kept backend-agnostic so that backend can slot in later without reworking the core. No graphical sources, framebuffer present, or controller input are built here.

## Core insight

The determinism refactor made `game-loop` purely **input-driven** (blocking `read-key`) to protect bit-identical replay — which removed wall-clock and froze idle animation. In the browser the freeze is even harder: wasm `read-key` calls `Atomics.wait`, and Go-wasm is single-threaded/cooperative, so a blocking read freezes the **entire** runtime including any clock goroutine.

The fix is to recognize **two orthogonal event streams**:

- a **deterministic input stream** — keypresses that mutate the world, folded into the seed and logged (already handled by `dispatch`);
- a **cosmetic clock stream** — frame ticks that advance animation only, never recorded.

A responsive UI is just the **merge** of those two streams folded by a `step`, where the loop **never blocks while idle** (it polls `key-pending?` and only calls `read-key` once a key is queued). That single poll-based contract is what makes it both browser-safe and replay-safe.

## 1. Architecture

```
 input-source ─┐
               ├─►  merge (concat of per-tick polls)  ─►  xform  ─►  step (scan)  ─►  render
 clock-source ─┘        no blocking while idle           transducers   {:world :ui}    subscriber
        (optional native-only sources adapt a scoped goroutine via chan-source)

 lifecycle: (with-scope [sc] … )  — cancels ctx-aware chan-source producers; the polling core needs no teardown
```

- **Owning loop, poll-based.** Each iteration collects events by polling every source (non-blocking), runs them through an optional transducer `xform`, folds them through `step` (this is `scan`), calls `render`, then paces via `pause!` (`(<!! (timeout …))` with the `*in-wasm*` ≥30ms floor — **not** `Thread/sleep`, so the JS event loop actually yields under wasm). Exit when `step` returns `(reduced result)`.
- **No idle blocking.** `read-key`/`Atomics.wait` is entered only when `key-pending?` is true, so the runtime never freezes. This is mandatory for wasm and harmless for native.
- **What prevents the leak: the poll-based core, not the scope.** PR #51's "orphaned `read-key` goroutine steals the next keypress" gotcha is eliminated because the core **never spawns a goroutine that blocks in `read-key`** — input is polled (`key-pending?` then `read-key` only when ready). This is the actual fix; do not credit it to the supervisor.
- **What `with-scope` actually buys (and its limit).** `scope-close!` (`pkg/vm/scope_gls.go`) **cancels the scope context and drains up to a timeout**, then warns if goroutines are still live. Cancellation only interrupts ops that **select on `(vm/CurrentContext)`** (cancellable channel ops, `alts!`, ctx-aware native ops). A goroutine blocked in a **non-ctx-aware** call — `read-key`/`Atomics.wait`, or `reflect …Recv()` (which the runtime notes a cancel "won't interrupt") — is **not** interrupted; it parks until the call returns and the drain times out (leaking until then). Therefore any `chan-source` producer **must be ctx-aware** (select on the scope context). A naive blocking-read producer is forbidden — use a polling source instead.

## 2. Data Model

### Event shape
A tagged vector: `[tag & payload]`. Two kinds, and **the action source is irrelevant to `step`**:
- `[:action act]` — a domain action (a keyword like `:move-left`/`:quit`, or a parameterized map like `{:type :use-item :id …}`). Emitted by *any* input source: the live keyboard (`input-source` maps keystroke → action at the edge), a menu sub-loop (which produces parameterized actions directly), or `replay-source` (replaying `:action-log`). `step` never sees a raw key and never parses one.
- `[:tick frame]` — a cosmetic frame tick; `frame` is a monotonically increasing integer owned by the clock source.
- (future) `[:net msg]`, additional `[:tick … ]` rates — same contract.

**Key→action normalization lives in the source, not in `step`.** `input-source` is just a raw-key reader composed with a `map input/parse-key` — i.e. parsing is a source-edge operator, exactly where Rx would put it. This keeps `step` a pure function of *actions*, so live input, replay, and menu sub-flows all drive it identically. There is no keypress converter inside the loop.

### Loop state — explicitly two-part
```clojure
{:world <deterministic sim state>   ; the only thing dispatch/replay ever see
 :ui    <cosmetic state>}           ; e.g. {:frame 0 :particles …}, never replayed
```
Invariant: `step` routes `[:tick …]` to `:ui` only and never calls `det/advance` or appends to `:action-log`. `replay` reconstructs `:world` from `(seed, action-log)` and ignores `:ui` entirely.

### Source contract
A source is a value supporting a non-blocking `poll` returning a (possibly empty) seq of events. Concretely a 0-arg fn (closing over its own mutable cursor) or a small map `{:poll (fn [] …)}`. `poll` MUST NOT block.

## 3. Components

> **Note — `xsofy/ui.lg` already exists** with layout helpers (`rect`, …) and a `pause!` timing helper (`(<!! (timeout (if *in-wasm* (max ms 30) ms)))`, added in PR #53). This module is *extended*, not created. Its `pause!` is the seed of the clock pacing; `clock-source`/`run-loop` build on the same `*in-wasm*`-aware timing.
>
> **Reconcile with PR #52** (`cleanup/ownership`) which introduces a **shared screenfx module**. The event loop must *drive* that module (render subscriber side), not duplicate its animation primitives. Sequencing/dependency on #52 is a rollout question (see §7).

### New
- **`xsofy/test/ui_test.lg`** — unit + determinism + loop tests (see §6).

### Extended
- **`xsofy/ui.lg`** — add the event-loop surface alongside the existing helpers:
  - `run-loop` — the driver (opts map: `:sources :xform :state :step :render :frame-ms`).
  - `input-source` — drains **all** keys queued this tick (not one), each normalized to an action at the edge: `(loop [out []] (if (and (term/key-pending?) (< (count out) 16)) (recur (conj out [:action (input/parse-key (term/read-key))])) out))`. The ≤16/frame cap prevents a pathological input flood from stalling a frame; one decoded key (escape sequences assembled) per `read-key`. No multi-frame backlog, ≤1 frame latency.
  - `clock-source` — `(clock-source ms)`; emits `[:tick frame]` when `ms` elapsed since its last emit (tracks its own last-emit + frame counter).
  - `replay-source` — `(replay-source actions)`; yields recorded actions as `[:action act]` events for headless replay; emits nothing once drained.
  - `poll-source` — `(poll-source f)`; the generic polling source: `f` is a non-blocking `() → event | events | nil` called once per tick. `input-source` and `clock-source` are specializations. **This is the default for async sources too**: feed a channel from anywhere and consume it with `(poll-source #(poll! ch))` (non-blocking `poll!`, `async.go:908`). No goroutine is owned by the source, nothing to cancel, works native + wasm — the leak-free path.
  - `chan-source` — `(chan-source ch producer)`; the **push** adapter, native-only, for genuinely-blocking upstreams. Spawns `producer` as a goroutine **inside the loop's scope**; the loop still consumes `ch` via non-blocking `poll!`. **Constraint (per §1):** `producer` MUST be ctx-aware — it must `select` on `(vm/CurrentContext)` (or use cancellable channel ops) so `scope-close!` can actually stop it. A producer that blocks in `read-key`/a raw syscall is **not** cancellable and is disallowed; use `poll-source` instead. Not needed by any in-scope source today (input + clock are both polling) — included only to define the seam for a future ctx-aware network source.
  - `scan` — small stateful transducer if/where a pipeline wants accumulation before `step` (the primary scan is `step` itself).

### Modified
- **`xsofy/title.lg`** — title screen uses `run-loop` (input + `clock-source 120`); `step` advances particle frame on `[:tick]`, returns `(reduced …)` on any `[:action]`. Removes `read-key-chan` / `make-key-poller` / `drive-until-key`.
- **`xsofy/render.lg`** (death screen) — same `run-loop` adoption for the death-screen pulse.
- **`main.lg`** (`game-loop`) — migrate the play loop onto `run-loop`: input events flow through deterministic `dispatch`; a clock source enables idle/ambient animation and folds the existing between-turn `:vfx` animation into the unified loop. Reconcile `*in-wasm*` branches (poll-on-tick removes some of the wasm special-casing around `read-key`).
- **`docs/terminal.md`** — add a "Responsive UIs" section (see §below) documenting the pattern + the determinism caveat.

### Unchanged
- `xsofy/dispatch.lg`, `xsofy/det.lg`, `xsofy/world.lg`, replay — the deterministic core. This module composes them; it does not alter them.

### Test files
- `xsofy/test/ui_test.lg` (new). Existing `xsofy/test/title_test.lg` updated to drive via the new loop with a fake clock + scripted input.

## 4. Data Flow

### A single idle frame (any animated screen)
1. `events = (mapcat poll sources)` — e.g. clock returns `[[:tick 7]]`, input returns `[]` (no key buffered).
2. `events` run through `:xform` (optional filter/map).
3. `state' = (reduce step state events)` — `[:tick 7]` advances `:ui :frame`; `:world` untouched.
4. `(render state')` paints **and presents** the frame. Presentation is the render subscriber's job (terminal: write + `term/flush`; graphical: blit/swap), so the loop core stays backend-agnostic — it never calls `term/flush` itself.
5. Pace the frame via the existing **`pause!`** primitive — `(<!! (timeout (if *in-wasm* (max ms 30) ms)))`, **not** `Thread/sleep`. Under single-threaded Go-wasm this is the proven path that **yields to the JS event loop** (so the SAB refills with keypresses and the frame presents), and it inherits the ≥30ms wasm floor `ui.lg` already established. `:frame-ms` is the *requested* cadence, floored under wasm exactly as `pause!` floors it. (A graphical backend may swap pacing for a vsync/`present`-driven pace via the same `:frame-ms` seam.) Recur.

### A keypress on the play screen
1. `input-source` sees `key-pending?` → reads `"h"`, parses it → `[[:action :move-left]]`.
2. `step` classifies the action via `dispatch/replay-action?`: world-mutating → `(dispatch/dispatch world action)` (seed folded, action logged); pure-UI → `(world/update-world world action)`; quit → `(reduced :quit)`. No parsing happens here — `step` only ever sees `[:action …]`.
3. `:ui` continues animating from concurrent `[:tick]`s the same iteration.

### Modal & multi-step interaction flows
A modal menu is itself a **nested `run-loop`** with its own sources/`step`/`render`, terminating via `(reduced <result | nil>)` (`nil` = cancelled). Because a `run-loop` *returns a value*, multi-step flows compose as **plain sequential code** that chains sub-loops and assembles one parameterized action — no flow framework needed:

```clojure
;; "apply" → select item → (optional) select target → one assembled action
(defn apply-flow [world]
  (when-let [item (run-item-select world)]           ; nested run-loop, nil = cancel
    (if (needs-target? item)
      (when-let [tgt (run-target-select world item)]  ; conditional second step
        {:type :apply :item item :target tgt})
      {:type :apply :item item})))                    ; target-less branch
```

The parent play loop's `step`, on the `:apply` key, runs `apply-flow` and—if it returns non-`nil`—dispatches the assembled action. Properties this gives:
- **Conditional / branching steps** are ordinary `if`/`when`; **cancellation** at any step (`nil`) aborts the whole flow with no action dispatched.
- **Determinism:** the entire flow — every intermediate menu keystroke and animation — is **pure-UI**. Only the single assembled `{:type :apply …}` action reaches `dispatch` (seed + `:action-log`). Menu navigation is never folded or logged.
- **Replay:** `replay-source` emits the assembled `[:action {:type :apply …}]` and `step` dispatches it directly — the multi-step UI is **never re-run** during replay, so bit-identity holds regardless of how the action was originally assembled.

### Replay (headless / test)
1. `sources = [(replay-source action-log)]` — **no clock source**. Events arrive as `[:action act]` — the same shape live input produces, so the source is interchangeable.
2. `step` folds recorded actions through the exact same routing as live input. `:world` is reconstructed bit-identically; absence of `[:tick]`s means `:ui` simply never advances. Determinism preserved by construction.

## 5. Error Handling

- **Teardown on any exit.** `run-loop` body is inside `with-scope` + `try/finally`; the caller's terminal shutdown (`shutdown-terminal`) remains the outer `finally`. Normal exit, `:quit`, and exceptions all `scope-close!` (cancel + drain up to `*scope-drain-timeout-ms*`). This stops **ctx-aware** `chan-source` producers; the polling core (input + clock) owns no goroutines, so its teardown is a no-op. If a non-ctx-aware producer were ever used (disallowed, per §1), the drain would time out with a warning — the test in §6 guards against leaks.
- **EOF / nil key.** When `read-key` returns `nil` (EOF), `input-source` emits `[:action :eof]` (the source maps the nil through; `parse-key` is only called on a real key); `step` may treat `:eof` as exit. Native and wasm both covered (wasm `read-key` returns nil on a zero/oversized length).
- **Source poll throwing.** A source `poll` that throws aborts the iteration and propagates to the `finally` (no partial-event swallowing); sources are expected to be total and non-blocking.
- **Determinism guard (test-enforced, not runtime).** The replay test (§6) is the guard that `[:tick]` never perturbs `:world`; there is no runtime cost in the hot loop.

## 6. Testing Strategy

### Unit tests
- `input-source` returns `[]` when nothing is pending and `[[:action a]]` (key already parsed via `input/parse-key`) when a key is queued (fake `key-pending?`/`read-key`); a fake key maps to its expected action.
- `clock-source` emits a tick only after `ms` elapsed; frame counter increments monotonically (injected clock).
- `step` for each screen: feed event seqs, assert resulting `:world` and `:ui`.

### Determinism test (the core property)
- Run the same `step` over an action-log twice: once with `[:tick]` events interleaved at arbitrary positions, once with none. Assert `:world` (and `(seed, action-log)`) are **bit-identical** across both. This is the property the determinism refactor must never regress.

### Loop tests
- `run-loop` driven by a fake clock + scripted input source terminates on the exit key and produces the expected final state — generalizes PR #51's `drive-until-key` coverage in `title_test.lg`.

### Cross-runtime
- A native test asserts no goroutine leak after `run-loop` exits (scope `scope-live` returns to baseline).
- **wasm pacing smoke test (verification task, PR1).** Build let-go → wasm (`wasm/Makefile`) and run a timing probe: a background goroutine increments a counter while the main path paces via `pause!`; assert the counter advances (the JS event loop progressed) across frames. This empirically confirms the pacing primitive yields under wasm — and would reveal whether `Thread/sleep` could ever substitute (it must not be assumed). Full input-bridge validation still happens manually in xsofy's Worker+SAB Pages harness.

## 7. Rollout Plan

**Depends on: PR #52** (`cleanup/ownership`, shared `screenfx.lg`). #52 is small, mergeable, and `screenfx.lg` already exists — land it first. From PR2 on, the loop's **render subscriber drives `screenfx.lg`** rather than introducing parallel animation primitives.

### PR1 — `xsofy/ui.lg` + `ui_test.lg`
The module and its tests, with no consumer changes. Land the source contract, `run-loop`, `input-source`, `clock-source`, `poll-source` (and the `chan-source` seam, ctx-aware-only), `replay-source`, and the determinism + loop tests + the wasm pacing smoke test (§6). Self-contained; no behavior change to existing screens.

### PR2 — Title + death screens
Migrate `title.lg` and the death-screen pulse to `run-loop`; delete `read-key-chan`/`make-key-poller`/`drive-until-key`; update `title_test.lg`. Render subscriber drives `screenfx.lg` for the pulse/particles. This closes the original PR #51 bug via the general mechanism. (PR #51 itself is superseded — either rebased to this or closed in favor of PR2.)

### PR3a — Core play-loop swap
Migrate the play loop onto `run-loop`: input → `dispatch`, clock-driven idle/ambient animation (via `screenfx.lg`), fold the between-turn `:vfx` path into the unified loop, and reconcile the `*in-wasm*` branches. Proves the core play loop end-to-end; existing modal menus keep their current handling until 3b.

### PR3b — Modal menus & multi-step flows
Port the modal menus (`:inventory`/`:use-menu`/`:drop-menu`/`:equip-menu`/`:rune-codex`) and the multi-step `apply→item→target` flows to **nested `run-loop`s** (the §4 pattern). Can land incrementally, even menu-by-menu — each menu's nested loop is independent. Highest detail surface, isolated from the core swap.

### PR4 — Docs
`docs/terminal.md` "Responsive UIs" section: the poll-based source contract, the input+clock multiplex, why blocking `read-key` freezes wasm, and the determinism caveat (cosmetic clock must never enter the seed/replay log). Note `key-pending?` as the portable primitive.

## `docs/terminal.md` addition (outline)

> ### Responsive UIs: multiplexing input and a cosmetic clock
> - Why blocking `read-key` can't animate (native: input-driven only; wasm: `Atomics.wait` freezes the single-threaded runtime).
> - The poll-on-tick contract: `key-pending?` + non-blocking sources.
> - `xsofy.ui/run-loop` usage with `input-source` + `clock-source`.
> - **Determinism caveat:** clock ticks advance UI state only — never fold them into a seed or replay log.
