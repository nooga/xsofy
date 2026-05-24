# Spell DSL Specification v3

## Overview

Spells are flat sequences of rune tokens that compile to a pipeline of operations on a context. The player sees rune glyphs; the engine evaluates s-expressions.

```
Player sees:    ᚠ ᛊ 3 ᛁ
Engine evals:   (-> ctx fire (area 3) ice)
English:        fire, then freeze area of radius 3
```

There are no parentheses in rune notation. Structure comes from one combinator: **apply**.

## The Two Layers

### Runic Notation (player-facing)

A flat sequence of rune glyphs. Read left-to-right. Each glyph maps to a primitive.

```
F             fire (default)
FI            fire, then ice
aF3I          fire(3), then ice
aF3aI2        fire(3), then ice(2)
CDs           conjure(dragon), self
aCDf          conjure(dragon), friend
```

### S-expression (engine)

The pipeline that gets evaluated:

```clojure
(-> ctx fire)
(-> ctx fire ice)
(-> ctx (fire 3) ice)
(-> ctx (fire 3) (ice 2))
(-> ctx (conjure :dragon) self)
(-> ctx (conjure :dragon) friend)
```

The `ctx` and `->` are implicit — never shown to the player.

## Grammar

### Tokens

Every rune is one of:

| Category    | Examples              | Notes                        |
|-------------|-----------------------|------------------------------|
| Primitive   | fire, ice, arc, push  | Effect or selector rune      |
| Numeral     | 1, 2, 3, 4, 5        | Literal number               |
| Apply       | a                     | The sole structural combinator |
| Math        | mul, add              | Arithmetic, always binary    |
| Property    | mind, body, essence   | Arguments to take/give       |
| Entity type | dragon, rat, door     | Arguments to conjure         |

### Parsing Rules

1. **Default**: each token produces one pipeline step with no arguments.
   - `F I` → `(fire) (ice)` → two separate steps

2. **Apply (`a`)**: consumes the next two tokens, produces `(fn arg)`.
   - `a F 3` → `(fire 3)` → one step, fire with power 3
   - `a C D` → `(conjure :dragon)` → one step
   - Nested: `a a F M 2 3 I` → `(fire (mul 2 3)) (ice)` → `(fire 6) (ice)`

3. **Math (`M`, `A`)**: always consumes next two tokens. Exception to the arity-0 rule.
   - `M 2 3` → 6 (product)
   - `A 3 2` → 5 (sum)
   - Nest with apply: `a F M 2 3` → `(fire (mul 2 3))` → `(fire 6)`
   - Non-numeric math operands are spell programming errors.

### Parser Algorithm

```
parse(tokens) → steps:
  while tokens not empty:
    token = pop(tokens)
    if token is 'apply':
      fn = parse_one(tokens)
      arg = parse_one(tokens)
      emit (fn arg)
    elif token is 'mul':
      a = parse_one(tokens)
      b = parse_one(tokens)
      emit (* a b) or error if a/b are not numbers
    elif token is 'add':
      a = parse_one(tokens)
      b = parse_one(tokens)
      emit (+ a b) or error if a/b are not numbers
    else:
      emit (token)  ;; bare primitive, no args
```

This is a trivial recursive descent parser. No ambiguity. No lookahead beyond one token.

## Programming Errors and Backfire

Rune programs can be malformed. That is part of the magic system: misunderstood reality calls should misfire in-world, not crash the engine.

Evaluation is left-to-right. If a step has already changed the world, that change remains. When the evaluator reaches an invalid step, it stops the spell and returns a structured error with the offending rune, reason, and arguments.

Examples:

```clojure
[:apply :fire 2]
;; valid: fire with power 2

[:apply :fire :ice]
;; error: fire expects a number argument, but got the ice rune
;; no fire is applied

[:fire :apply :damage :ice]
;; fire is applied first
;; then damage errors because ice is not a number
```

Current runtime backfire rule:

| Source             | Result |
|--------------------|--------|
| Player item rune   | Prior effects remain, then the offending rune glows crimson and zaps the player for 1 damage. |
| Creature rune      | Prior effects remain, then the offending rune glows crimson and rings unpleasantly. |

The message should expose that a rune failed without explaining the full API contract. The player sees a crimson warning tied to the item or creature source, and can infer which program shape is unsafe through experimentation.

## Context

```clojure
{:world    world          ;; current world state
 :caster   entity-id      ;; who cast this
 :origin   [x y]          ;; caster position
 :cursors  #{[x y]}       ;; where the spell is currently acting
 :result   nil             ;; numeric result of last effect
 :hits     #{}             ;; entities already affected (for arc dedup)
 :vfx      []}             ;; visual events for animation
```

### Default Context by Medium

| Medium          | Default cursors              |
|-----------------|------------------------------|
| Dagger/sword    | melee target (adjacent cell) |
| Bow/crossbow    | projectile to aimed cell     |
| Scroll          | caster (self)                |
| Wand/staff      | aimed cell (ranged)          |
| Trap            | entity that triggered it     |
| Potion          | caster (self)                |

Same rune, different item, different effect. `fire` on a dagger = flaming strike. `fire` on a scroll = self-immolation.

## Primitives

### Selectors — set cursors (arity 0)

| Rune       | Effect                                     |
|------------|--------------------------------------------|
| `self`     | cursors = caster position                  |
| `target`   | cursors = aimed cell                       |
| `nearest`  | cursors = nearest visible entity to cursor |

### Cursor Modifiers — transform cursors

| Rune       | With apply        | Without apply         |
|------------|-------------------|-----------------------|
| `area`     | `(area N)` radius | `(area 1)` default    |
| `spread`   | `(spread N)` count| `(spread 2)` default  |

### Effects — do things at cursors

| Rune       | With apply        | Without apply           |
|------------|-------------------|-------------------------|
| `fire`     | `(fire N)` power  | `(fire)` default power  |
| `ice`      | `(ice N)` power   | `(ice)` default freeze  |
| `damage`   | `(damage N)`      | `(damage 1)` poke       |
| `heal`     | `(heal N)`        | `(heal 1)` tiny heal    |
| `poison`   | `(poison N)` turns| `(poison 2)` default    |
| `arc`      | —                 | lightning damage + jump  |
| `push`     | `(push N)` force  | `(push 1)` nudge        |
| `conjure`  | `(conjure type)`  | `(conjure)` random      |

### Modifiers

| Rune       | With apply        | Without apply           |
|------------|-------------------|-------------------------|
| `friend`   | —                 | make last conjured ally |
| `repeat`   | `(repeat N)`      | `(repeat 2)` do twice   |
| `wait`     | `(wait N)` turns  | `(wait 1)` next turn    |

### Transfer

| Rune       | Takes             | Effect                  |
|------------|-------------------|-------------------------|
| `take`     | property rune     | take from entity at cursor |
| `give`     | property rune     | give to entity at cursor   |

Always used with apply: `a take mind` → `(take :mind)`

### Properties (arity 0, used as arguments)

| Rune       | Meaning                              |
|------------|--------------------------------------|
| `mind`     | consciousness, memories, rune knowledge |
| `body`     | physical form, stats                 |
| `essence`  | everything — full entity swap        |
| `life`     | HP/vitality only                     |
| `form`     | appearance, glyph                    |

### Entity Types (arity 0, used as arguments)

| Rune       | Meaning       |
|------------|---------------|
| `rat`      | summon rat    |
| `goblin`   | summon goblin |
| `dragon`   | summon dragon |
| `door`     | summon door   |
| `wall`     | create wall   |

### Numerals (arity 0)

| Rune | Value |
|------|-------|
| `1`  | 1     |
| `2`  | 2     |
| `3`  | 3     |
| `4`  | 4     |
| `5`  | 5     |

### Math (always binary, consumes next two tokens)

| Rune  | Operation | Example       |
|-------|-----------|---------------|
| `mul` | multiply  | `M 3 4` → 12 |
| `add` | add       | `A 3 2` → 5  |

### Time

| Rune       | With apply  | Without apply      |
|------------|-------------|--------------------|
| `future`   | `(future N)`| `(future 3)` peek 3 turns |
| `past`     | `(past N)`  | `(past 3)` rewind 3 turns |

### Special

| Rune       | Effect                                        |
|------------|-----------------------------------------------|
| `blank`    | resolves to default value for any consumer    |
| `result`   | resolves to numeric result of last effect     |

## Example Spells

### Simple (no apply needed)

```
F           → (fire)              — default fire at cursor
I           → (ice)               — default freeze at cursor
FI          → (fire) (ice)        — fire then freeze
P           → (push)              — push entities away
AAA         → (arc) (arc) (arc)   — chain lightning x3
sH          → (self) (heal)       — heal self (tiny)
```

### With Apply

```
aF3         → (fire 3)            — fire with power 3
aH5         → (heal 5)            — heal 5 HP
aD4         → (damage 4)          — deal 4 damage
aR2aH3      → (area 2) (heal 3)  — heal 3 in radius 2
aD2saH2     → (damage 2) (self) (heal 2)  — hit then lifesteal
aCDs        → (conjure :dragon) (self) — conjure dragon at self?
aCDf        → (conjure :dragon) (friend) — summon friendly dragon
```

### With Math

```
aDM34       → (damage (mul 3 4))  → (damage 12) — massive damage
aFM25       → (fire (mul 2 5))    → (fire 10)   — inferno
aHA23       → (heal (add 2 3))    → (heal 5)    — heal 5
```

### Advanced

```
;; Vampiric strike: damage 3, then heal self for 3
aD3saH3     → (damage 3) (self) (heal 3)

;; Area freeze: expand to radius 2, then ice
aR2I        → (area 2) (ice)

;; Chain lightning x4: arc four times
AAAA        → (arc) (arc) (arc) (arc)

;; Repeat fire 3 times: powerful DOT
aW3F        → (repeat 3) (fire)   — fire 3 times? or wait 3 then fire?
;; Actually: wait takes remaining pipeline
;; aW3F = defer fire for 3 turns. Time bomb!

;; Summon 3 rats
aE3C        → (repeat 3) (conjure) — 3 random creatures

;; Mind swap with nearest
na take mind → (nearest) (take :mind) — become nearest entity

;; Become a door
aCdoora take essence → (conjure :door) (take :essence) — you're a door now
```

### Dangerous Misfires

```
;; Scroll of fire (default context = self)
F           → (fire)  — player is at cursor. You set YOURSELF on fire.

;; Thought it was heal, it's actually damage
aD5         → (damage 5) — at self on a scroll. Ouch.

;; Past when you meant future
aP3         → (past 3)  — you just erased 3 turns of progress

;; Conjure with no friend modifier
aC          → (conjure) — random hostile creature right next to you
```

## Rune Identification

Each run randomizes the mapping: keyword → glyph + garbled name.

- **Unidentified**: player sees glyph `ᚠ` and garbled name "vorth"
- **Identified**: player sees glyph `ᚠ` and true name "fire"
- **Glyph is permanent**: same glyph for the whole run, only the name reveals

### Identification Methods

1. **Scroll of Identify** — reveals one random rune mapping
2. **Experimentation** — cast it and observe (risky)
3. **Observation** — watch NPC use a runic weapon, infer from effect
4. **Deduction** — if you know `fire` and see `aᚠ3` cause a big fire, you know `a` is apply and `3` is three

### Knowledge Mechanics

- Knowledge stored on player entity as `:known-runes`
- `take :mind` transfers knowledge
- `future` (precognition) preserves knowledge on snap-back
- `past` (rewind) does NOT preserve knowledge — you lose what you learned

## Variables (future)

Reserve a rune for "store" and "recall" — push/pop a value register:

```
store X ... recall X
```

This enables computed spells: store a damage amount, use it later for healing. Store the result of one effect, apply it to another. Turing complete? Maybe. That's fine.

## Cost Economics — Enchantment Points + Mana

*(Design direction recovered from 2026-05-17 sessions; not yet implemented.)*

Every rune **evaluated** costs one point, drawn in cascade order:

1. A point of the **item's enchantment level**, if any remains.
2. A point of the caster's **mana**, once enchantment is exhausted.

This unifies fuel/budget, per-rune evaluation cost, and cursed-item dynamics
into a single resource model. Discovery is therefore an *economic* choice —
you cannot observe the language for free.

**Failed runes still cost.** Two models were weighed:

| Model        | Rule                                   | Trade-off |
|--------------|----------------------------------------|-----------|
| **Full cost** *(preferred)* | 1 per failed step, same as success | Simple, unifies the cost model, punitive |
| Fizzle tax   | Flat 1 HP regardless of steps failed   | Friendlier discovery, but a special case |

## Discovery — Incomplete Spells Leak by Category

*(Design direction recovered from 2026-05-17; not yet implemented.)*

Incomplete or failed spells must **not** fail silently. Each failed rune emits
**category-level** feedback — proportional but indistinct — so players can
reverse-engineer the language by reading the runtime trace (the "isekai
programmer discovers the compiler" framing). Crucially, leaks reveal the
*category*, never the specific rune.

| Category                              | Leak on failure                                   |
|---------------------------------------|---------------------------------------------------|
| Elemental (fire, ice, poison, arc)    | spark / frost / wisp / crackle VFX at cursor, no damage |
| Damage / impact (damage, push, arc)   | 1 HP damage flash at target                       |
| Heal / life                           | 1 HP heal with glow                               |
| Mana / transfer (take, give, friend)  | 1 MP drain/grant between caster and target        |
| Selectors (self, nearest, target)     | brief highlight of would-be cursor positions      |
| Cursor modifiers (area, spread)       | highlight modified-area cells                     |
| Math / structural (apply, mul, add, blank, result) | **no leak** (purely structural)      |
| Time (future, past)                   | temporal shimmer at caster                        |

This composes with the parser-leak guard below: leaks are *deliberate,
categorical* signals; internal error-maps are not.

### Parser-leak guard (property)

The parser produces error-maps mid-stream
(`{:reason :bad-argument :rune :add :args [nil nil] :error true}`). These must
never reach the player. Target property:

> For any parseable rune sequence, `format-sexp` returns a string containing no
> `:error`, `:reason`, or other internal-shape markers.

## The Five-Role Model

*(Recovered from 2026-05-17. The positions-as-cursors decision is already live;
the `:container` role is the main open gap — see below.)*

Every spell resolves through five roles. Borrowed loosely from MTG's
actor/subject framing:

| Role            | In ctx       | Meaning                                              |
|-----------------|--------------|------------------------------------------------------|
| **Actor**       | `:caster`    | entity that initiated the spell                      |
| **Subject**     | `:cursors`   | positions where effects apply (**positions, not entities**) |
| **Container**   | `:container` *(planned)* | the item the runes fire from               |
| **Action**      | each step    | the effect itself                                    |
| **Environment** | `:world`     | shared world state all spells resolve into           |

**Targets are positions, not entities** — intentional, so one spell can:
damage whoever stands on a tile (now or after `:wait` steps), light empty tiles,
push from vacated cells, freeze water, or transmute terrain.

## Container-Aware Runes (the main open gap)

*(Recovered from 2026-05-17 / 2026-05-20. Partially implemented.)*

The source item is a first-class part of a spell's identity. Different
container types fire on different triggers:

| Container | Trigger        | Status                                            |
|-----------|----------------|---------------------------------------------------|
| Weapon    | on-hit (melee) | **Live** — `world.lg` melee-attack reads `(:runes wep)` |
| (creature)| on-hit / on-shot | **Live** — falls back to `(:runes attacker)` / fires `(:spell-runes attacker)` at projectile impact |
| Armor     | on-take-hit    | **Gap** — inscribable & stored, but never fired   |
| Ring      | on-turn        | **Gap** — slot exists, nothing fires              |
| Shield    | on-block       | **Gap** — not yet in slot system                  |
| Wand/staff| on-use         | **Gap** — designed, unimplemented                 |

Plan: add a `:container` field to ctx so runes can query their source item,
behave by container type (e.g. "amplify if container is +3"), drain HP from
cursed items, and restrict to slot types. **Design this as one unified
container-rune system, not per-item patches** — armor (hit-triggered), ring
(per-turn), shield (block-triggered), and wand (use-triggered) share the
mechanism, differing only in trigger event.

> Note: container-aware runes are *why armor can't be inscribed usefully today* —
> inscription works, but `damage-entity` never reads `(:runes armor)` on the
> defender, so the runes are dead data. The fix is the trigger layer, not the
> parser.

## Creature Abilities Are Rune Programs

*(Recovered from 2026-05-17. Core mechanism is live; named signatures are the
next layer.)*

Creatures and players share **one evaluator** (`spell/eval-spell`). Monster
abilities are not hardcoded — they are rune sequences inscribed on the creature
entity, fired through the same pipeline:

- Melee: `(:runes attacker)` fires on hit (`world.lg` melee-attack).
- Ranged: `(:spell-runes attacker)` fires at projectile impact (`ranged-attack`).

**Cones and breath are just cursor shapes.** Area/directional effects come from
selector + modifier runes, not bespoke code:

```clojure
;; dragon fire breath — already live in bestiary.lg
{:spell-runes [:apply :area 2 :fire]}    ; (area 2) fire

;; ice cone
{:spell-runes [:apply :area 1 :ice]}     ; (area 1) ice

;; spider web-on-hit
{:spell-runes [:web]}

;; vampiric melee (fires via :runes on hit)
{:runes [:apply :damage 2 :self :apply :heal 1]}
```

### Signature spells (planned)

High-tier creatures ship pre-named, multi-rune programs as innate runes. The
fire-imp's spell is bare `(fire)`; an elder fire-drake's
`(area 5) (repeat 3) fire (push)` is **"Cinder Storm"** — the creature
*announces* it, the spell takes 2–3 turns to wind up, and the player has time to
retreat. Wind-up time scales with program length.

### Magic resistance (planned)

Unlike armor's flat reduction, magic resistance is **percentage-based**,
enabling partial transfers rather than binary save-or-fail. A wraith with 50%
resistance, drained for 4 MP, yields the caster 2 MP. Players learn how
resistance varies across creatures by repeated experimentation — discovery
gameplay.

## Implementation Notes

### Total Rune Count

```
Selectors:     3  (self, target, nearest)
Modifiers:     2  (area, spread)
Effects:       8  (fire, ice, damage, heal, poison, arc, push, conjure)
Modifiers:     3  (friend, repeat, wait)
Transfer:      2  (take, give)
Properties:    5  (mind, body, essence, life, form)
Entity types:  5  (rat, goblin, dragon, door, wall)
Numerals:      5  (1-5)
Math:          2  (mul, add)
Time:          2  (future, past)
Special:       3  (blank, result, apply)
─────────────────
Total:        ~40 runes
```

Each randomized per run into ~40 glyphs and garbled names. The player identifies them one at a time.

### Items Store Raw Rune Sequences

```clojure
;; flaming dagger
{:runes [:fire]}

;; vampiric blade
{:runes [:apply :damage 2 :self :apply :heal 2]}

;; thunder hammer
{:runes [:arc :arc]}
```

The rune sequence is stored as keywords. The display layer looks up glyphs from the rune table. The eval layer compiles to the pipeline.
