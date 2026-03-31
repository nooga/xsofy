# Spell DSL Specification

## Overview

Spells are pipelines expressed as Clojure threading macros. A spell is a sequence of rune words that, when evaluated, produces a function `context -> [world-states]` — a lazy sequence of world snapshots for animation.

```clojure
(-> ctx target fire)              ;; set target on fire
(-> ctx nearest (take :mind))     ;; become the nearest entity
(-> ctx arc arc arc arc)          ;; chain lightning
(-> ctx (damage 3) self (heal 2)) ;; hit then self-heal
```

Every game action is a spell — melee attacks, ranged attacks, item effects, NPC abilities. The spell system IS the physics engine.

## Context

Every spell executes within a context that flows through the pipeline. Each rune-word is a function `ctx -> ctx` that transforms the context and may emit animation frames.

```clojure
{:world    world          ;; current world state
 :caster   entity-id      ;; who cast this
 :origin   [x y]          ;; caster position
 :cursors  #{[x y]}       ;; where the spell is currently acting (set of positions)
 :carrying nil             ;; property being transferred by take/give
 :result   nil             ;; numeric result of last effect (damage dealt, etc.)
 :hits     #{}             ;; entities already affected (for arc/spread dedup)
 :frames   []}             ;; accumulated animation frames (world snapshots)
```

### Default Context

The context is pre-populated based on the spell's medium:

| Medium          | Default cursors              |
|-----------------|------------------------------|
| Dagger/sword    | melee target (adjacent cell) |
| Bow/crossbow    | projectile to aimed cell     |
| Scroll          | caster (self)                |
| Wand/staff      | aimed cell (ranged)          |
| Trap            | entity that triggered it     |
| Potion          | caster (self)                |
| Innate (NPC)    | depends on AI intent         |

This means the same rune inscribed on different items behaves differently. A single fire rune on a dagger = flaming melee strike. Same rune on a scroll = set yourself on fire.

## Rune Parsing

Runes are parsed left-to-right with **greedy fixed-arity** consumption. Each rune word has a known arity. The parser reads one word, then consumes the next N words as its arguments (recursively, since arguments may themselves have arity > 0).

A flat rune sequence becomes a nested s-expression which is then threaded through `->`.

### Example Parse

```
;; rune sequence (english names):
damage 3 spread 3 damage 1

;; arity table: damage=1, spread=1, numerals=0
;; greedy parse, left to right:
;; step 1: damage(1) needs 1 arg → consumes 3 → (damage 3)
;; step 2: spread(1) needs 1 arg → consumes 3 → (spread 3)
;; step 3: damage(1) needs 1 arg → consumes 1 → (damage 1)
;; result: three pipeline steps

;; threaded:
(-> ctx (damage 3) (spread 3) (damage 1))
```

### Pipeline Separation

Top-level expressions with arity 0 or fully-saturated expressions at the top level become separate pipeline steps. The parser collects them into a list and the evaluator threads context through sequentially.

## Rune Dictionary

Each run generates a randomized mapping from rune glyphs/names to these primitives. Until identified, runes show a garbled name and symbol from a per-run procedural dictionary.

### Display

| State        | Shown as                         | Example            |
|--------------|----------------------------------|--------------------|
| Unidentified | garbled name + random glyph      | "vorth" ᚦ          |
| Identified   | english name + consistent glyph  | "fire" ᚦ           |

The glyph stays the same — only the name changes when identified. This way the player can start recognizing glyphs visually before full identification.

### Garbled Name Generation

Each run seeds a name generator that produces ~30 garbled names from phoneme tables. Names should feel like fragments of a dead language — pronounceable but alien. Examples: "vorth", "kael", "thryn", "zeph", "moldri", "ashk", "quelp", "ixen".

### Glyph Pool

A pool of unicode glyphs assigned randomly per run. Drawn from runic, alchemical, and miscellaneous symbol blocks:

```
Runic:     ᚠ ᚢ ᚦ ᚨ ᚱ ᚲ ᚷ ᚹ ᚺ ᚾ ᛁ ᛃ ᛇ ᛈ ᛉ ᛊ ᛋ ᛏ ᛒ ᛖ ᛗ ᛚ ᛜ ᛞ ᛟ
Alchemical: 🜁 🜂 🜃 🜄 🜅 🜆 🜇 🜈 🜉 🜊 🜋 🜌 🜍 🜎 🜏 🜐 🜑
Misc:       ◈ ◊ ○ ● △ ▽ ☉ ☽ ♁ ♃ ♄ ♅ ♆ ⊕ ⊗ ⊘ ⊛ ⊜
```

---

## Primitives

### Selectors (arity 0) — set cursors

| Name         | Effect                                              |
|--------------|-----------------------------------------------------|
| `self`       | cursors = caster position                           |
| `target`     | cursors = aimed cell (from player input or AI)      |
| `nearest`    | cursors = nearest visible entity to caster          |
| `all-visible`| cursors = all entities in caster's FOV              |

### Cursor Modifiers (arity 1) — transform cursors

| Name         | Arity | Effect                                            |
|--------------|-------|---------------------------------------------------|
| `spread`     | 1     | cursors = N nearest entities to current cursors, deduped vs :hits |
| `area`       | 1     | cursors = all cells within N radius of current cursors |

### Effects (arity 0 or 1) — do things at cursors, emit frames

| Name         | Arity | Effect                                            |
|--------------|-------|---------------------------------------------------|
| `fire`       | 0     | add fire effect at cursors                        |
| `ice`        | 0     | add ice effect at cursors, extinguish fire        |
| `arc`        | 0     | lightning damage at cursor, move cursor to nearest unhit, emit bolt frame |
| `push`       | 0     | push entities at cursors away from origin         |
| `pull`       | 0     | pull entities at cursors toward origin            |
| `destroy`    | 0     | destroy entity/tile at cursors                    |
| `projectile` | 0     | animate projectile from origin to cursor, visual + hit confirm |
| `damage`     | 1     | deal N damage at cursors, result = total dealt    |
| `heal`       | 1     | heal N at cursors, result = total healed          |
| `poison`     | 1     | apply poison for N turns at cursors               |
| `create`     | 1     | create entity of type at cursors                  |
| `transmute`  | 1     | change entity/tile at cursors into type           |

### Transfer (arity 1) — move properties between entities

| Name         | Arity | Effect                                            |
|--------------|-------|---------------------------------------------------|
| `take`       | 1     | remove property from entity at cursor, store in :carrying, copy to caster |
| `give`       | 1     | take property from caster, store in :carrying, copy to entity at cursor |

### Properties (arity 0) — arguments to take/give

| Name         | Meaning                                             |
|--------------|-----------------------------------------------------|
| `:mind`      | consciousness, memories, identity, known runes      |
| `:body`      | physical form, stats, HP, equipment                 |
| `:essence`   | everything — full entity swap                       |
| `:life`      | HP/vitality only                                    |
| `:form`      | appearance, tile glyph, name                        |

### Flow Control

| Name         | Arity | Effect                                            |
|--------------|-------|---------------------------------------------------|
| `wait`       | 1     | split spell across turns — remaining steps execute N turns later |
| `repeat`     | 1     | execute remaining steps N additional times         |

### Time (arity 1) — affect the game loop

| Name         | Arity | Effect                                            |
|--------------|-------|---------------------------------------------------|
| `future`     | 1     | begin precognition for N turns (Caves of Qud style) |
| `past`       | 1     | rewind world N turns, discard future               |

### Numerals (arity 0)

| Name | Value |
|------|-------|
| `1`  | 1     |
| `2`  | 2     |
| `3`  | 3     |
| `4`  | 4     |
| `5`  | 5     |

### Special

| Name   | Arity | Effect                                             |
|--------|-------|----------------------------------------------------|
| `blank` | 0    | resolves to the default argument for whatever consumes it |
| `result`| 0    | resolves to the numeric :result from the last effect |

---

## Arity Table (for parser)

```clojure
{self 0, target 0, nearest 0, all-visible 0,
 spread 1, area 1,
 fire 0, ice 0, arc 0, push 0, pull 0, destroy 0, projectile 0,
 damage 1, heal 1, poison 1, create 1, transmute 1,
 take 1, give 1,
 wait 1, repeat 1, future 1, past 1,
 1 0, 2 0, 3 0, 4 0, 5 0,
 blank 0, result 0,
 :mind 0, :body 0, :essence 0, :life 0, :form 0}
```

Total: ~33 primitives = ~33 runes per run.

---

## Example Spells

### Combat Basics

```clojure
;; dagger attack (default ctx = melee target)
[damage 2]
(-> ctx (damage 2))

;; bow shot (default ctx = projectile to aimed cell)
[damage 3]
(-> ctx (damage 3))

;; same rune, different weapon = different spell

;; fireball — fire at target area
[target (area 2) fire (damage 3)]
(-> ctx target (area 2) fire (damage 3))

;; ice lance — projectile, freeze on hit
[target projectile ice (damage 4)]
(-> ctx target projectile ice (damage 4))
```

### Chain Effects

```clojure
;; chain lightning — arc 4 times
[arc arc arc arc]
(-> ctx arc arc arc arc)

;; chain heal — heal nearest, spread to 3, heal those too
[nearest (heal 3) (spread 3) (heal 2)]
(-> ctx nearest (heal 3) (spread 3) (heal 2))

;; lifesteal — damage target, heal self for result
[target (damage 4) self (heal result)]
(-> ctx target (damage 4) self (heal result))
```

### Transfer

```clojure
;; mind swap with nearest
[nearest (take :mind)]
(-> ctx nearest (take :mind))

;; body swap with target
[target (take :body)]
(-> ctx target (take :body))

;; become a door (take essence of nearest door... if you can target one)
[target (take :essence)]
(-> ctx target (take :essence))

;; give your form to an enemy (you become invisible? they become you?)
[nearest (give :form)]
(-> ctx nearest (give :form))
```

### Time

```clojure
;; precognition — play 5 turns, snap back with knowledge
[(future 5)]
(-> ctx (future 5))

;; rewind 3 turns
[(past 3)]
(-> ctx (past 3))

;; delayed fireball — goes off in 3 turns at original target
[target (wait 3) (area 2) fire (damage 5)]
(-> ctx target (wait 3) (area 2) fire (damage 5))

;; poison — damage over time
[target (damage 1) (wait 1) (damage 1) (wait 1) (damage 1)]
(-> ctx target (damage 1) (wait 1) (damage 1) (wait 1) (damage 1))
;; or just: [target (poison 3)]
```

### Emergent Chaos

```clojure
;; duplicate self (clone the player??)
[self (create :essence)]
(-> ctx self (create :essence))

;; turn all visible enemies into walls
[all-visible (transmute :wall)]
(-> ctx all-visible (transmute :wall))

;; chain mind-swap — steal nearest mind, spread, steal again
;; become a hive mind?
[nearest (take :mind) (spread 3) (take :mind)]

;; fire + push = fire explosion that knocks enemies back
[target (area 3) fire push (damage 2)]

;; pull everything to you then freeze
[all-visible pull ice]

;; give your life force to heal someone
[target (give :life)]
```

### Dangerous Misfires

```clojure
;; player casts unknown rune on self (scroll default)
;; turns out to be: [destroy]
(-> ctx destroy)  ;; ctx default is self. you destroyed yourself.

;; player thinks this is heal, it's actually take-mind on self
;; nothing happens? or you lose your memories?

;; (past 5) when you meant (future 5)
;; you've erased 5 turns of progress instead of scouting
```

---

## Animation Model

Each primitive may emit animation frames as part of its execution. A frame is a world snapshot representing one visual tick (~30-50ms).

Evaluation is lazy — the game loop consumes frames one at a time:

```clojure
(defn animate! [world-seq]
  (doseq [w world-seq]
    (render! w)
    (when-not (skip-pressed?)
      (sleep! 30))))
```

- Walking around: 0-1 frames, instant
- Melee hit: 2-3 frames (swing, impact)
- Projectile: 1 frame per cell traveled
- Fire spread: 1 frame per expansion ring
- Chain lightning: 1-2 frames per arc
- Explosion: 1 frame per ring
- Time travel: visual rewind effect (play history backwards rapidly)

Chain reactions extend the sequence. Fire hitting gas appends explosion frames. The world sim step after spell resolution may produce its own frames (cellular automata spreading fire/gas/water).

## Turn Structure

```
1. Player input (wait for keypress)
2. Parse action as spell
3. Evaluate spell → lazy [world-states]
4. Animate spell frames
5. For each NPC:
   a. AI selects spell
   b. Evaluate → lazy [world-states]
   c. Animate
6. Environment step (fire/gas/water CA) → lazy [world-states]
7. Animate environment
8. Snapshot final world state → append to history
9. Check pending spells (wait/delay), evaluate any that trigger
10. Goto 1
```

## Identification System

### Per-Run Rune Table

At world generation, a seeded RNG creates:

1. A mapping from each of ~33 primitives to a unique glyph
2. A mapping from each of ~33 primitives to a garbled name
3. A boolean per rune: identified or not (all start false)

```clojure
{:rune-table
 {fire      {:glyph "ᚠ" :garbled "vorth" :identified false}
  ice       {:glyph "ᚢ" :garbled "kael"  :identified false}
  damage    {:glyph "ᚦ" :garbled "thryn" :identified false}
  ...}}
```

### Identification Methods

- **Scroll of Identification** — reveals one random unidentified rune
- **Experimentation** — cast it and observe the effect (risky)
- **Rune stones** — found in the dungeon, inscribed with one rune + a hint
- **NPC spellcasters** — watch what they cast, infer from effects
- **Combining known + unknown** — if you know `target` and `damage` and cast `[target ??? damage 3]`, and something freezes, the unknown is `ice`
- **Scrolls/books of lore** — reveal rune names in the "dead language" which are the garbled names — meaning the garbled name IS a clue once you know the language

### Knowledge Persists Through Mind

The player entity's `:mind` property includes `:known-runes`. Mind-swap transfers this knowledge. Precognition (`future`) preserves it on snap-back. Rewind (`past`) does NOT preserve it — you lose what you learned.

This creates a tension: `past` is powerful (actual undo) but costs knowledge. `future` is safe but temporary.
