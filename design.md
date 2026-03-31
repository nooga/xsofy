# Xs of Y — A Roguelike Where Magic is Lisp

## Elevator Pitch

A roguelike where the title, rules, and magic system are procedurally generated each run. The magic system is raw lisp — concatenated runes that are secretly s-expressions. The player has root access to the game's physics engine but the documentation is in a dead language that changes every boot.

Brogue meets Noita. The idea predates Noita.

## Core Concept

### The Rune System

- Each game generates ~20 rune glyphs (ᚠ ᚢ ᚦ ᚨ ᚱ ᛃ ᛇ ᛈ ᛉ ᛊ ...)
- Each rune secretly maps to a lisp primitive: `fire`, `ice`, `self`, `target`, `area`, `heal`, `damage`, `push`, `pull`, `duplicate`, `delay`, `chain`, `transmute`, `mind`, `transfer`, ...
- Rune mappings are randomized per run (seeded)
- Player concatenates runes into spells: `ᚠᛊᚨ` = `(fire area target)` = fireball at target
- The player doesn't know the mapping — they find rune stones, experiment, take notes
- Some combinations are dangerous: `ᚠᛊᚢ` = `(fire area self)` = nuke yourself
- The spell system is literally `eval` on a shuffled DSL

### The Key Insight

The runes aren't a "magic system bolted onto a game". They're raw syscalls to reality. The player is a script kiddie who found a terminal into the world's physics engine but the man pages are in a dead language. The magic is hilariously OP but highly dangerous.

The developer doesn't implement "brain swap" or "time travel" as features. The developer implements a small set of composable primitives:
- `transfer` — move properties between entities
- `self` — the caster
- `target` — what you're pointing at
- `mind` — the cognitive properties subset
- `time` — temporal operations

And the player discovers that `ᚦᛃᚨ` means `(transfer mind target)` and realizes they can become anything. A door. A torch. An eel. The floor.

### Emergent Scenarios

- You figure out the "self" rune the hard way
- `(duplicate duplicate anything)` = exponential chaos
- `(delay N spell)` creates time bombs — cast a spell that goes off in 5 turns, hope you decoded it right
- `(transmute wall gold)` breaks the dungeon economy. `(transmute self gold)` — you're a statue, game over
- Brain swap with a door. Your body walks away. You're a door now. Someone opens you
- Brain swap with an ogre, wait, pick up loot, go back in time (Caves of Qud mindrot vibes)
- Finding a scroll that reveals one rune mapping is more valuable than any weapon
- Speedrunners try to identify the minimum rune set needed to break the game

### Power Curve

Inverted from normal roguelikes:
- Early game: terrified of your own spells
- Mid game: decoded enough to be dangerous (to yourself and others)
- Late game: basically a god, but there's always that one untested combination...

## Architecture

### Why let-go is Perfect

- **Persistent data structures** — every turn is an immutable world snapshot
- **Free undo/replay** — game history is a list of world states
- **Time manipulation is just list indexing** — `(nth history (- turn 5))`
- **Spell eval is literally eval** — the game IS the language
- **Fast startup** — 6ms, instant feel
- **Standalone binary** — `lg -b xsofy main.lg`, distribute single file
- **WASM build** — playable in browser eventually

### World State

```clojure
{:terrain   (byte-array ...)        ; 80x50 tile type grid (static, rarely changes)
 :effects   {[3 4] #{:fire :gas}    ; sparse dynamic state (what's happening per cell)
             [5 6] #{:wet}}
 :entities  {:player {:pos [10 20] :hp 15 :mind {:known-runes {...} :memory {...}}}
             :goblin-1 {:pos [30 25] :hp 5 :ai :patrol}
             :door-7 {:pos [15 20] :type :door :state :closed}}
 :turn      42
 :history   [...previous states...]} ; persistent — structural sharing makes this cheap
```

### Layered Performance Strategy

- **Static terrain**: `byte-array` (tile types, rarely changes)
- **Dynamic effects**: persistent map, sparse (only active cells: fire, gas, water — typically <100 entries)
- **Entities**: persistent map by id
- **Cellular automata**: iterate effects map only, not all 4000 tiles
- **Turn history**: list of `{:terrain :effects :entities}` — terrain pointer shared across turns

### Performance Budget

- Turn-based: ~10 keys/second max
- Per turn: ~50 entity moves + ~50 cell updates + LOS checks = ~500 operations
- fib(35) benchmark: ~30M function calls in 2.1s → ~6000x headroom
- Spell eval: 5-10 node expression tree = microseconds
- Chain reactions (gas explosion → fire spread → water boil): worst case ~200k cell updates, still fine
- Rendering bottleneck: dirty rectangle tracking, only redraw changed tiles

### Rendering

Terminal first (ANSI escape codes), WASM/browser later. Game logic is pure data transforms, rendering is a separate layer.

### Spell Engine

Runes evaluate as nested function application against the world state:

```clojure
;; Rune sequence: ᚠᛊᚨ
;; Decoded: (fire area target)
;; Evaluation: apply fire effect to area around target

(defn eval-spell [world caster rune-seq]
  (let [expr (decode-runes (:rune-map world) rune-seq)
        context {:world world :caster caster}]
    (apply-spell-expr context expr)))
```

The primitives are the game's physics API:
- **Verbs**: fire, ice, push, pull, heal, damage, transmute, transfer, create, destroy, duplicate, delay
- **Nouns**: self, target, area, wall, floor, nearest-entity, all-visible
- **Modifiers**: chain, repeat, amplify, invert, conditional

## Procedural Title Generation

"Xs of Y" where both are generated:
- X: Daggers, Crypts, Echoes, Whispers, Fractures, Coils, ...
- Y: Ennui, Symmetry, Rust, Forgetting, Teeth, Recursion, ...

The title could subtly hint at the run's rune mappings or world gen parameters.

## Open Questions

- How many rune primitives before it's overwhelming? (15-25 seems right)
- Should some rune combinations be "recipes" found on scrolls/books?
- Physical rune stones as inventory items vs memorized spells?
- How does the AI use runes? Enemy spellcasters who know the mapping?
- Multiplayer potential? Two players with different rune knowledge cooperating?
- How to teach the player the concept without a tutorial? (First rune stone + forced safe experimentation room?)

## Implementation Plan

1. **Spell DSL + eval engine** — the soul of the game, prototype standalone
2. **World sim** — tiles, cellular automata (fire/gas/water), entity system
3. **Terminal rendering** — ANSI, raw input, dirty rectangles
4. **Map generation** — BSP rooms + corridors or cellular automata caves
5. **FOV / lighting** — shadow casting (Brogue's lighting is iconic)
6. **Entity AI** — simple state machines, pathfinding
7. **Game loop** — tie it all together
8. **WASM port** — browser version
