# Balancing & Tuning Tools

xsofy has a small set of dev tools for checking whether the game plays the way
we intend. They answer different questions, so pick by question:

| Question | Tool | Author |
|---|---|---|
| Across many runs, who dies, where, and to what? | Balance harness (`xsofy/balance.lg`) | @nnunley |
| Does this one interaction (fire + water, webs, …) behave right? | Scenario test (`tools/scenariotest.lg`) | @mparrett |
| Is this generated level well-formed? | Map viewer + audit (`tools/mapgen.lg`, `tools/mapaudit.lg`) | @mparrett |
| How does lighting actually look and fall off? | Lighting testbed (`tools/lighttest.lg`) | @mparrett |
| How much does a turn cost? | Native bench (`bench/native.lg`) + `xsofy.perf` | @mparrett |
| What's the state of the run I'm playing right now? | Dev console (`` ` `` in-game) | @mparrett |

A typical loop: the **harness** flags a statistical signal ("slimes kill
starters on depth 1"), a **scenario** isolates the interaction behind it, and a
**replay** or **console** dump pins the exact run for a bug report.

All tools run under `lg` (let-go) from the repo root.

---

## Balance harness — statistical survivability

`xsofy/balance.lg` runs synthetic players through real, seeded dungeons and
aggregates the outcomes. It is an evidence generator, not a pass/fail test:
trust the **comparative** numbers (loadout vs. loadout, rune vs. no rune), not
absolute reach rates, which mostly measure bot quality.

- **Loadouts:** `:starter`, `:starter-with-damage-rune`, `:heavy-melee`,
  `:ranged` (extend `all-loadouts` to add more).
- **Policies:** `:greedy-melee`, `:greedy-descend`, `:random`,
  `:passive-rest`, and `:ensouled` (perception-driven, via `xsofy.percept`).
- **Output:** per-loadout died/stuck/reached counts, mean turns survived,
  deaths by depth, and deaths by creature (`:killed-by` is inferred from the
  message log).

```clojure
(require '[xsofy.balance :as b])
(b/print-report
  (b/explore {:n-runs 4 :loadouts [:starter] :policies [:greedy-melee]
              :max-turns 200 :target-depth 3 :seed 42}))
```

Or run the example script: `lg xsofy/test/balance_explore.lg` (small defaults).

**Cost:** about 9–10 ms per turn natively (let-go 1.13, measured with
`bench/native.lg`), so worst-case wall time is `n-runs × max-turns × 0.01 s`
plus ~1 s of startup. The snippet above takes ~5 s and the script ~4 s. Start
small; the defaults (`n-runs 40`, `max-turns 2000`) can take up to ~13
minutes.

**Reproducing a run:** every outcome carries `:seed` and `:action-log`.
Replay the log on the same *loadout* world. Plain `dispatch/replay` starts from
the default starting kit and diverges:

```clojure
(require '[xsofy.dispatch :as dispatch])
(reduce dispatch/dispatch
        (b/make-loadout-world 50 30 (get b/all-loadouts (:loadout outcome)) (:seed outcome))
        (:action-log outcome))
```

Full details, an example workflow, and the 2026-05-30 sample sweep and its
findings are in [`balance-harness.md`](balance-harness.md).

---

## Scenario test — isolate one interaction

`tools/scenariotest.lg` builds a hand-made arena from an EDN file, with no
dungeon generation, and drives it through the **real** play loop
(`xsofy.play/run-play-loop`): the same dispatch, `update-world`, and render
path as the game. Use it to reproduce a balance or interaction bug without
hunting for a seed.

```bash
lg tools/scenariotest.lg tools/scenarios/web-trample.edn          # interactive (needs a TTY)
lg tools/scenariotest.lg --check tools/scenarios/water-burning.edn  # build + summarize, no TTY
```

Scenario keys (all optional; see `tools/scenariotest/build.lg`):

| Key | Meaning |
|---|---|
| `:name`, `:desc`, `:log` | Label and boot messages |
| `:w`, `:h` | Arena size (default 16×10; the border is always stone wall) |
| `:seed`, `:depth` | Gameplay seed and floor depth |
| `:fill`, `:paint` | Interior tile id, then `[[x y tile] …]` overlays |
| `:player` | `{:pos [x y] :statuses {…} :body {…}}` |
| `:creatures` | `[{:species :fire-imp :pos [x y] :ai {…} :merge {…}} …]`: `:ai`/`:merge` patch a spawned creature (e.g. force a 100% fire chance) without forking the bestiary |
| `:gear?` | `true` gives the normal starting items |

The shipped scenarios (`water-burning.edn`, `web-trample.edn`) each document
the bug they reproduce, the keys to press, and the expected behavior before and
after the fix. Copy one as a template. Menus other than hazard and quit prompts
are dismissed, and death ends the session.

---

## Map viewer + audit — level quality

`tools/mapgen.lg` generates levels with the live generator and dumps them to
stdout as ASCII (24-bit color by default), with a one-line audit per level:

```bash
lg tools/mapgen.lg '{:seed 42 :depth 5}'
lg tools/mapgen.lg '{:seed 100 :count 20 :color false}'   # flip through 20 consecutive seeds
```

Keys: `:seed` (random if omitted), `:w 79`, `:h 30`, `:depth 1`, `:count 1`,
`:color true`.

`tools/mapaudit.lg` provides the metrics as pure functions, so tests can assert
on them too. `(audit grid w h)` returns:

- `:doors`: door count
- `:floating`: doors that aren't a real room threshold (mid-corridor, or not in
  a clean one-wide passage)
- `:unreach`: passable tiles cut off from the rest of the level

A clean level has `floating=0` and `unreach=0`.

---

## Lighting testbed

`tools/lighttest.lg` shows a dungeon with a light you steer by hand (or leave on
an animated demo path), so you can see falloff, radius, color mixing, and FOV
occlusion directly. No monsters or turns. It also works as a render-performance
probe: `e` cycles between delta, SGR-off, and naive redraw modes, and the HUD
reports the changed cell count and color changes per frame.

```bash
lg tools/lighttest.lg '{:seed 42}'
```

The controls are listed in the file's docstring and on screen.

---

## Engine benchmark

`bench/native.lg` times the headless engine per turn (avg/p50/p95/max over
several seeds) on four fixtures: idle `:wait`, `:autoex`, `:descend`, and a
long `:busy` explore. The core lives in `xsofy.perf` and is shared with the
in-bundle WASM harness, so native and browser numbers can be compared
directly. Units differ: `bench/native.lg` prints milliseconds, while the
console's `(bench)` reports microseconds (`:unit :us`), so `:avg 8871` there
is 8.9 ms.

```bash
lg bench/native.lg
```

Use it to size balance-harness sweeps (per-turn cost drives their wall time)
and to confirm that a balance change didn't make turns slower.

---

## Dev console

Backtick opens a dev console in the running game (native and browser). It
only appears in dev mode: set `XSOFY_DEV=1` natively
(`XSOFY_DEV=1 lg main.lg`) or add `?mode=dev` to the web URL. It is
read-only by design: commands come from a fixed whitelist and nothing is
`eval`'d, so it can't change the seed or the action log.

| Command | Returns |
|---|---|
| `(replay)` | Replay code for this run (also emitted to the web shell) |
| `(seed)` | Seed input and current rolling seed |
| `(player)` | HP, max HP, position, depth |
| `(location)` | Position, depth, turn |
| `(bench)` | Quick micro-benchmark (3 seeds × 20 turns; results in μs) |
| `(help)` | Command list |

Commands that take no arguments also work bare: `seed` is the same as `(seed)`.

When a playtest turns up something that feels off (an unfair death, a spike in
difficulty), grab `(replay)` so the exact run can be replayed.

---

## See also

- [`balance-harness.md`](balance-harness.md): full harness reference and sample findings
- `xsofy/percept.lg`: perception layer used by the `:ensouled` policy
- `xsofy/dispatch.lg`, `xsofy/replay.lg`: deterministic dispatch and replay
- `tools/scenarios/`: example scenario files
