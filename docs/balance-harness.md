# Balance Harness — Survivability Testing

## Purpose

The balance harness (`xsofy/balance.lg`) is a **statistical evidence generator** for dungeon-game balance tuning. It runs synthetic players through procedurally generated dungeons with various loadouts and AI policies, collecting outcomes (survived, died, where, to what creature, how long) to answer questions like:

- At what depth does a starter loadout (dagger + leather) die?
- Does a rune inscribed on the dagger meaningfully shift survival curves?
- Which creatures kill the most players, and at what depth?
- Does an aggressive melee policy outperform a "rush downstairs" strategy?
- How does a perception-driven AI compare to heuristic-only policies?

**This is NOT a correctness or property test.** It produces observational data from realistic gameplay, not formal guarantees.

## Loadouts

A loadout specifies the player's starting equipment, armor, and inventory. Supported loadouts (defined in `xsofy/balance.lg`):

- **`:starter`** — Dagger + leather armor + 2 health potions (baseline)
- **`:starter-with-damage-rune`** — Same, but dagger inscribed with `:damage` rune (7 HP boost per hit)
- **`:heavy-melee`** — Long sword + chain mail + 3 health potions (slower but more durable)
- **`:ranged`** — Short bow + arrows + leather + 2 health potions (damage-at-distance test)

Custom loadouts can be added by extending the `all-loadouts` map.

## Policies (AI Behaviors)

Each run is controlled by an AI policy that decides the next action each turn. The harness includes:

- **`:greedy-melee`** — Rush to the nearest visible enemy and attack; if no enemy visible, route to known stairs or autoexplore.
- **`:greedy-descend`** — Prioritize reaching stairs over combat; only fight if in the way (or on encounter).
- **`:random`** — Step in a random direction each turn (baseline chaos).
- **`:passive-rest`** — Stay in place and rest (for measuring passive damage over time, e.g., starvation or status effects).
- **`:ensouled`** — Perception-driven AI using the `xsofy.percept` layer:
  - Flees when HP < 30% or burning/frozen
  - Attacks the highest-threat enemy if in range
  - Routes to stairs if low on health
  - Otherwise explores

The `:ensouled` policy demonstrates how a more sophisticated perception and utility-scoring AI can be integrated into the harness. It is a template for future AI experiments.

## How to Run

### From the REPL

```clojure
(require '[xsofy.balance :as b])

; Quick smoke test (4 runs, should finish in ~10 seconds)
(def report (b/explore {:n-runs 4 
                        :loadouts [:starter] 
                        :policies [:greedy-melee]
                        :max-turns 200 
                        :target-depth 3 
                        :seed 42}))
(b/print-report report)

; Medium sweep (40 runs across 2 loadouts × 2 policies)
(def report (b/explore {:n-runs 40
                        :loadouts [:starter :starter-with-damage-rune]
                        :policies [:greedy-melee :ensouled]
                        :max-turns 2000
                        :target-depth 9
                        :seed 999}))
(b/print-report report)
```

### As a Script

```bash
lg xsofy/test/balance_explore.lg
```

The script runs with **small parameters by default** (n-runs=6, max-turns=200, target-depth=3) for quick feedback. See the script (`xsofy/test/balance_explore.lg`) to adjust for longer sweeps.

## Runtime Cost & Warnings

⚠️ **A turn takes ~20–25 ms.** Wall time = n-runs × max-turns × ~0.02 seconds.

Default parameters (n-runs=40, max-turns=2000) = 40 × 2000 × 0.02 = **~27 minutes of continuous computation** with **no progress output**. This is the bug we fixed.

### How to Avoid the Silent 30-Minute Hang

1. **Start small.** Always use small parameters on first run:
   ```clojure
   {:n-runs 3 :max-turns 60 :target-depth 3}  ; ~3 seconds
   ```
   Watch the progress lines appear in real time (one per completed run).

2. **Estimate before scaling.** If 3 runs × 60 turns = 3.6 seconds, then 40 runs × 2000 turns = ~27 minutes (40/3 × 2000/60 ≈ 450 wall time).

3. **Use `:seed` for reproducibility.** Include a `:seed` in your params so you get the same run sequence every time. Without it, timings vary due to procedural generation differences.

## Reproducibility via Seed + Replay

Each outcome record includes `:seed` (the initial RNG seed for that run) and `:action-log` (the list of actions the policy took). You can **reconstruct any run exactly**:

```clojure
(require '[xsofy.dispatch :as dispatch])

; Outcome from a previous run
(def outcome {...
              :seed 12345
              :action-log [:autoexplore :autoexplore :up ...]})

; Replay: same seed + same actions = identical world
(def replayed (dispatch/replay 50 30 (:seed outcome) (:action-log outcome)))
; replayed has the same :depth, :turn, :entities, :terrain, etc. as the live run
```

**Use case:** Found an interesting death? Save the outcome, replay it locally for debugging, or feed it to a visualizer.

## Output Format

### Outcome Record

Each run produces an outcome map:

```clojure
{:loadout :starter
 :policy :greedy-melee
 :outcome :died              ; :died | :reached-target | :stuck
 :turn 127                   ; turns played
 :depth 3                    ; dungeon depth reached
 :killed-by :goblin          ; creature that killed player (nil if survived)
 :hp-final 0                 ; final HP
 :final-pos [12 8]           ; final position
 :seed 12345                 ; RNG seed used (for replay)
 :action-log [...]           ; ordered sequence of actions taken
}
```

### Report Structure

`(b/explore opts)` returns:

```clojure
{:n-runs 40                  ; total runs
 :by-loadout 
   {:starter {:died 20, :reached-target 5, :stuck 15}
    :heavy-melee {:died 8, :reached-target 18, :stuck 14}}
 :death-by-depth
   {:starter {1 12, 2 6, 3 2}}
 :death-by-creature
   {:starter {:goblin 12, :slime 5, :fire-imp 2}}
 :mean-turns {:starter 89.4}
 :reach-rate {:starter 0.125}
 :outcomes [outcome1 outcome2 ...]}  ; raw outcome records for post-processing
```

Use `(b/print-report report)` to pretty-print it to stdout, or access fields directly for custom analysis.

## Integration with Percept

The `:ensouled` policy uses `xsofy.percept`, the perception layer. The percept is a structured view of the world from the player's perspective:

```clojure
(require '[xsofy.percept :as percept])

(def p (percept/perceive world :player))
; => {:self :player
;     :turn 5
;     :depth 1
;     :threats [{:id :goblin-3, :pos [...], :score 12.0, ...} ...]
;     :resources {:hp 20, :max-hp 30, :hp-frac 0.67, 
;                 :inventory {...}, :equipment {...}, ...}
;     :goals {:stairs-down [[...]], :nearest-stairs-down [...], ...}
;     :adjacency {[0 -1] {:tile 0, :walkable? true, ...}, ...}
;     :fov #{[10 12] [10 13] ...}}
```

Policies can use the full percept or convenience functions like `(percept/in-danger? world :player)` to make context-aware decisions. The harness keeps percept isolated so it doesn't affect reproducibility (it's called at action-decision time, not state mutation time).

## Testing the Harness

The main test suite confirms that:
- Worlds are deterministic: same seed → identical layout, items, runes
- Action logs replay exactly: seed + actions → identical final state
- Outcomes record correctly: loadout/policy/seed/actions are all captured

Run `./bin/lg xsofy/test/run.lg` to verify the harness compiles and tests pass.

## Limitations & Future Work

- **No parallelization yet.** Runs are sequential. For large sweeps (100+ runs), consider batching or async.
- **Procedural generation variance.** Different seeds produce different floor layouts, creaturecounts, etc. Use large n-runs for statistical confidence.
- **Policy is stateless.** Policies see the current world and return an action; they have no memory. Future: add agent state (e.g., "remember patrol routes").
- **No player-AI training loop.** This harness is for testing existing policies, not learning new ones. Future: integrate an optimization loop.

## Example Workflow

```clojure
; 1. Tune balance: does the damage rune help survival?
(def report1 (b/explore {:n-runs 40
                         :loadouts [:starter :starter-with-damage-rune]
                         :policies [:greedy-melee]
                         :max-turns 2000
                         :target-depth 9}))
(b/print-report report1)
; => starter: 12 died, 28 reached
;    starter-with-damage-rune: 8 died, 32 reached
; Conclusion: damage rune helps (~+14% reach rate).

; 2. Find interesting death: take one outcome
(def interesting-death (first (filter #(and (= :starter (:loadout %))
                                            (= :died (:outcome %)))
                                     (:outcomes report1))))

; 3. Replay it for debugging
(def replayed-world (dispatch/replay 50 30 (:seed interesting-death) 
                                    (:action-log interesting-death)))
(println "Replayed death at depth" (:depth replayed-world))

; 4. Ask follow-up questions
; - Did the policy make poor choices? (inspect :action-log)
; - Was the creature too strong? (check :killed-by)
; - Was the loadout unsuitable? (compare other loadouts)
```

## Sample Sweep & Findings (2026-05-30)

A 40-run sweep across all 4 loadouts × all 5 policies (`max-turns 250`, `target-depth 5`, `seed 7`), ~20s wall time:

```
Per-loadout outcomes:                          mean-turns survived
  starter:                   8 died, 2 stuck       72.0
  starter-with-damage-rune:  7 died, 3 stuck       80.9
  heavy-melee:               7 died, 3 stuck       63.5
  ranged:                    8 died, 2 stuck       75.8
  (reach-rate 0.0 for all — see note below)

Death by depth:
  starter:                   depth 1: 6, depth 2: 1, depth 3: 1
  starter-with-damage-rune:  depth 1: 6, depth 2: 1
  heavy-melee:               depth 1: 6, depth 2: 1
  ranged:                    depth 1: 6, depth 2: 2

Death by creature (top killers):
  starter:                   slime 3, archer 2, rat 2, status/env 1
  ranged:                    spider 3, slime 2, kobold 1, archer 1, rat 1
  heavy-melee:               archer 2, slime 2, kobold 1, spider 1, status/env 1
  starter-with-damage-rune:  kobold 2, rat 2, slime 1, spider 1, status/env 1
```

Signals worth a look (balance observations, not bugs):

- **The `:damage` rune measurably helps survival** — `starter-with-damage-rune` averaged 80.9 turns vs 72.0 for plain `:starter`.
- **`:heavy-melee` is the *worst* performer (63.5 mean-turns)** — it dies *faster* than the starter, which is counterintuitive. Likely the long-sword's speed penalty lets creatures land extra hits before the bot swings. Worth checking weapon-speed vs. survivability.
- **Top early killers:** slimes (all loadouts), spiders (punish `:ranged`), archers (ranged attackers hit everyone). `status/env` = burning/poison or lava/chasm deaths.
- **`reach-rate = 0.0`** — none of the heuristic bots reach `target-depth 5`. Absolute depth reflects *bot quality + early-game lethality*, so trust the **comparative** numbers (rune vs. no-rune, loadout vs. loadout), not the absolute reach rate.

Harness/agent limitation surfaced by the sweep:

- **`:ensouled` and `:passive-rest` frequently report `:stuck`** (e.g. `:ensouled → stuck (turn 41)` recurs). `:ensouled` emits many non-advancing actions (blocked moves), so the world advances only ~41 turns before the iteration cap. This is a **policy-quality issue in `:ensouled`** (stall / weak pathing), not a harness bug — a candidate for follow-up.

> Reproduce: `(b/explore {:n-runs 40 :loadouts [:starter :starter-with-damage-rune :heavy-melee :ranged] :policies [:greedy-melee :greedy-descend :random :passive-rest :ensouled] :max-turns 250 :target-depth 5 :seed 7})`

## See Also

- `xsofy/balance.lg` — full harness code
- `xsofy/percept.lg` — perception layer (used by `:ensouled` policy)
- `xsofy/dispatch.lg` — single-turn dispatcher and replay system
- `xsofy/test/balance_explore.lg` — example script
