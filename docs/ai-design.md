# AI System Design — Data-Driven Composable AI

## Problem

The current AI is a monolithic state machine with behavior checks inlined into the state transitions and actions. Adding new creature behaviors means inflating the central `cond` with more branches. Doesn't scale.

## Goal

Separate the **state machine engine** from **per-creature behavior**. Creatures define their AI as composable data, not code. The engine evaluates it generically.

## Core Concepts

### States

A state has:
- **action** — what the creature does each turn in this state (e.g., `:approach`, `:retreat`, `:ranged-attack`, `:random-walk`, `:idle`)
- **transitions** — predicates that trigger state changes, evaluated in priority order

### Context

Each tick, the engine builds a context map available to all predicates and actions:

```clojure
{:entity    ...        ;; the creature
 :pos       [x y]
 :player-pos [x y]
 :dist      5.3        ;; distance to player
 :sees-player true
 :hp-pct    0.6        ;; current/max
 :adjacent  false
 :has-los   true       ;; clear line of sight
 :behavior  :kiter     ;; from entity data
 :ai-data   {...}      ;; arbitrary per-creature AI params
 }
```

### AI Type

A reusable template defining states and their transitions:

```clojure
(def ai-melee
  {:states
   {:sleep     {:action :idle
                :transitions [[:sees-near?  :hunt]
                              [:wander-roll :wander]]}
    :wander    {:action :random-walk
                :transitions [[:sees-near?  :hunt]
                              [:sleep-roll  :sleep]]}
    :hunt      {:action :approach-and-melee
                :transitions [[:lost-player? :investigate]
                              [:low-hp?      :flee]]}
    :investigate {:action :go-to-target-or-search
                  :transitions [[:sees-near?     :hunt]
                                [:search-expired :sleep]]}
    :flee      {:action :retreat
                :transitions [[:far-or-hidden? :sleep]
                              [:hp-recovered?  :hunt]]}}})
```

### Composition

AI types compose by merging state maps. A ranged creature starts with the melee base and overrides/adds:

```clojure
(def ai-kiter
  (merge-ai ai-melee
    {:states
     {:hunt {:action :ranged-or-approach
             :transitions [[:too-close?   :flee]   ;; added
                           [:lost-player? :investigate]
                           [:low-hp?      :flee]]}
      :flee {:action :retreat-and-shoot
             :transitions [[:far-or-hidden?  :sleep]
                           [:at-pref-range?  :hunt]   ;; kiting recovery
                           [:hp-recovered?   :hunt]]}}}))
```

### Traits as AI Modifiers

Traits could wrap or modify an AI type:

- **`:pack`** — on entering `:hunt`, alert all creatures of same template within radius N. Adds a `:rally` transition.
- **`:coward`** — overrides `low-hp?` threshold to 50% instead of 25%.
- **`:berserker`** — removes `:flee` state entirely, adds damage bonus action in `:hunt` when low HP.
- **`:ambusher`** — stays in `:sleep` even when seeing player until adjacent, then surprise attack with bonus damage.
- **`:territorial`** — hunts only within N tiles of spawn point, returns to territory when player leaves.

```clojure
(defcreature goblin-archer
  (ai-type (-> ai-kiter (with-trait :pack)))
  (attack :ranged {:damage 2 :range 6 :vfx :bolt})
  (attack :melee {:damage 1}))
```

### Actions Registry

Actions are simple fns: `(fn [world entity-id ctx] -> world)`

```clojure
(def actions
  {:idle            (fn [w eid ctx] w)
   :random-walk     random-step
   :approach-and-melee  (fn [w eid ctx] ...)
   :ranged-or-approach  (fn [w eid ctx] ...)
   :retreat              (fn [w eid ctx] ...)
   :retreat-and-shoot    (fn [w eid ctx] ...)
   :go-to-target-or-search (fn [w eid ctx] ...)})
```

### Predicates Registry

Predicates are fns: `(fn [ctx] -> bool)`

```clojure
(def predicates
  {:sees-near?     (fn [ctx] (and (:sees-player ctx) (< (:dist ctx) 8)))
   :lost-player?   (fn [ctx] (and (not (:sees-player ctx)) (> (:dist ctx) 14)))
   :low-hp?        (fn [ctx] (< (:hp-pct ctx) 0.25))
   :hp-recovered?  (fn [ctx] (>= (:hp-pct ctx) 0.25))
   :too-close?     (fn [ctx] (< (:dist ctx) (get-in ctx [:ai-data :preferred-range] 3)))
   :at-pref-range? (fn [ctx] (>= (:dist ctx) (get-in ctx [:ai-data :preferred-range] 5)))
   :far-or-hidden? (fn [ctx] (or (> (:dist ctx) 14) (not (:sees-player ctx))))
   :wander-roll    (fn [ctx] (< (rand-int 100) 30))
   :sleep-roll     (fn [ctx] (< (rand-int 100) 10))
   :search-expired (fn [ctx] (<= (get-in ctx [:ai-data :search-turns] 0) 0))})
```

### Engine

The engine is ~15 lines:

```clojure
(defn ai-tick [world entity-id ai-type]
  (let [ctx (build-context world entity-id)
        state (get-in world [:entities entity-id :ai :state] :sleep)
        state-def (get-in ai-type [:states state])
        ;; check transitions in order
        new-state (or (some (fn [[pred target]]
                              (when ((get predicates pred) ctx) target))
                            (:transitions state-def))
                      state)
        ;; transition
        world (if (not= new-state state)
                (on-transition world entity-id state new-state)
                world)
        ;; execute action
        action-fn (get actions (:action state-def) identity)]
    (action-fn world entity-id ctx)))
```

## Migration Path

1. Keep current AI working as-is
2. Implement the engine + context builder
3. Port `:melee` behavior to data-driven format, verify parity
4. Port `:ranged`/kiter
5. Add new AI types (pack, coward, berserker, ambusher)
6. Remove old hardcoded state machine

## Open Questions

- Should transitions support guards that prevent re-entry (e.g., cooldown on flee→hunt)?
- How to handle one-shot effects on state entry (e.g., pack alert on entering hunt)?
- Can the action itself force a state change mid-turn (e.g., attack kills target → investigate)?
- Per-creature memory (patrol waypoints, home territory) — store in `:ai` map on entity?

## Current Direction: Derived Blackboard + Utility Scoring

The current direction is to move away from a monolithic per-creature state
machine and toward a deterministic utility-style AI built from:

1. branch-local input history,
2. normalized perceivable events,
3. a derived per-creature blackboard,
4. scored drives / intents,
5. deterministic action selection.

This fits xsofy's existing replay and time-travel model better than a large
authoritative AI-state map. Monsters should not carry opaque mutable "mental
state" that survives independently of the branch. Their working memory should be
reconstructed for a specific commit from the current branch's visible history.

### Core Pipeline

```clojure
input-dag
  -> normalized world events
  -> perception filter (per creature)
  -> derived blackboard
  -> scored intents
  -> chosen action
```

The split matters:

- **input DAG** remains canonical history,
- **state DAG** may cache materialized worlds plus derived AI views,
- **blackboards are not authoritative world state**,
- **scores are ephemeral** and are recomputed from the blackboard each turn.

### Determinism Rules

The AI must remain fully replay-safe.

- No raw randomness; all stochastic choices must use the deterministic seed flow.
- Ties break by stable ordering or deterministic rolls.
- Blackboards are reconstructed from the active branch only.
- Rewind / `switch-head` / `branch` / `undo` must never leak knowledge from
  abandoned or alternate futures.
- Garbage-collected branches lose their blackboards naturally because the
  history they were derived from is gone.

### Blackboard Model

Each creature gets a small derived blackboard for a given commit. It should hold
compact, useful beliefs rather than raw event logs:

```clojure
{:last-seen-target ...
 :last-heard-disturbance ...
 :recent-attacker ...
 :ally-distress ...
 :home-pos ...
 :threat-level ...
 :safe-route? ...
 :search-ttl ...}
```

The blackboard is reconstructed from a bounded event horizon.

### Memory Horizon

Each creature has a memory profile, and that profile is modifiable by
traits/corruptions.

Suggested knobs:

```clojure
{:turn-depth 8
 :event-cap 16
 :channels #{:sight :sound :harm :ally}
 :decay :linear}
```

This allows:

- animals with short tactical memory,
- intelligent enemies with deeper memory,
- `:pack`/`:territorial`/`:coward`-style modifiers,
- late-game corruption or substrate effects that distort memory rules.

### Event Model

Blackboards should be built from **normalized AI-facing events**, not raw action
history. Example events:

```clojure
{:type :saw-target :target-id ... :pos [x y] :turn 42}
{:type :lost-target :target-id ... :turn 45}
{:type :heard-disturbance :pos [x y] :loudness 6 :turn 43}
{:type :took-damage :from :player :amount 3 :turn 44}
{:type :ally-died :ally-id :dog-1 :pos [10 7] :turn 44}
{:type :door-opened :pos [9 7] :turn 41}
```

The perception layer decides which creatures could have observed a given event.

### Scored Drives

Instead of hardcoding one FSM per creature family, creatures score a reusable
set of drives:

- `:self-preserve`
- `:attack`
- `:hold-range`
- `:pursue`
- `:investigate`
- `:guard`
- `:protect-ally`
- `:rally`
- `:wander`
- `:return-home`

Traits and creature templates modify weights, gating, and action availability.

Hard overrides should still exist for true interruptions only:

- dead,
- stunned/frozen,
- webbed,
- other "cannot act normally" conditions.

These are execution constraints, not ordinary utility drives.

## Worked Example: Hunter with a Pack of Dogs

The hunter and the dogs should coordinate without sharing one mind.

### Shared Event Vocabulary

- `:saw-target`
- `:lost-target`
- `:heard-disturbance`
- `:took-damage`
- `:ally-bit-target`
- `:ally-died`
- `:handler-threatened`

### Hunter Blackboard

```clojure
{:last-seen-target ...
 :pack-status {:alive 2 :engaged 1}
 :threat-level ...
 :safe-range? ...
 :escape-route? ...}
```

### Dog Blackboard

```clojure
{:last-seen-target ...
 :handler-pos ...
 :target-engaged-by-pack? ...
 :recent-pack-distress ...
 :bite-opportunity? ...}
```

### Scoring Shape

Hunter priorities:

- `:hold-range`
- `:focus-fire`
- `:self-preserve`
- `:retreat` when the pack collapses

Dog priorities:

- `:protect-handler`
- `:chase`
- `:flank`
- `:finish-wounded`
- weaker `:self-preserve` unless modified

### Resulting Behavior

- One dog sees the player and emits a local rally signal.
- Nearby dogs reconstruct "target known / pack engaged" and score `:chase` or
  `:flank`.
- The hunter reconstructs "target screened by pack" and scores `:hold-range`.
- If a dog dies, remaining dogs may shift toward panic, rage, or defense, while
  the hunter may switch to retreat-and-shoot behavior.

This creates recognizable coordination without a bespoke "hunter-pack FSM."

## Worked Example: Town with Villagers and Shopkeepers

This is a useful stress test because it exercises social behavior rather than
combat pursuit only.

### Shared Event Vocabulary

- `:saw-stranger`
- `:heard-theft`
- `:saw-assault`
- `:door-broken`
- `:ally-panicked`
- `:guard-summoned`
- `:trade-started`

### Villager Blackboard

```clojure
{:home-pos ...
 :known-safe-spots [...]
 :recent-threat ...
 :crowd-panic-level ...
 :known-shopkeeper-distress ...}
```

### Shopkeeper Blackboard

```clojure
{:shop-pos ...
 :owned-area ...
 :suspicious-target ...
 :theft-reported ...
 :guards-alerted? ...}
```

### Scoring Shape

Villager priorities:

- `:routine`
- `:avoid-threat`
- `:flee-to-safe-spot`
- `:warn-others`
- `:return-home`

Shopkeeper priorities:

- `:tend-shop`
- `:watch-suspicious-target`
- `:protect-goods`
- `:call-guards`
- `:flee` when violence escalates

### Resulting Behavior

- In calm conditions, villagers follow routines and shopkeepers stay anchored to
  owned space.
- A suspicious arrival changes scores before open combat starts.
- Theft creates alarm events that propagate socially.
- Some villagers flee, some warn others, and shopkeepers may stay in place just
  long enough to escalate to guards.

This lets civic behavior emerge from the same event/blackboard/scoring model
used for dungeon ecology.

## Implications for Issue #40

This direction aligns with the broader world-design goals in issue #40:

- factions and social groups,
- pack rally,
- guarding / patrol / territoriality,
- support and repair roles,
- hazard-aware and habitat-aware behavior,
- corruption overlays that alter perception, memory, or drive weighting.

The key benefit is that new creature families are composed from:

- event channels,
- memory profile,
- drive weights,
- available actions,
- trait/corruption modifiers,

rather than by adding another special-case branch to a central AI `cond`.

## Communication Vocabulary and Renderers

Ally communication should not be a hidden AI-only side channel. Important
coordination must exist as perceivable world events with typed semantics and
payloads.

### Design Rule

Signals should be:

- **typed** — stable gameplay meaning (`:alarm`, `:rally`, `:mark-target`, ...)
- **payload-capable** — can carry target ids, positions, threat classes, etc.
- **world-visible** — heard or seen through the normal perception model
- **renderer-driven** — the player-facing expression is decoupled from the AI
  semantics

This avoids telepathy while still letting different factions express the same
 tactical meaning in different ways.

### Core Signal Shape

```clojure
{:type :mark-target
 :from :hunter-1
 :faction :hunters
 :pos [12 8]
 :target-id :player
 :target-pos [15 8]
 :channel :sound
 :strength 6
 :turn 42}
```

Suggested common fields:

- `:type` — the gameplay intent
- `:from` — source entity id
- `:faction` — source alignment/group
- `:pos` — emission origin
- `:channel` — `:sound`, `:visual`, possibly later `:rune`/`:machine`
- `:strength` / `:radius` — how far the signal propagates
- `:turn` — deterministic recency anchor
- payload fields like `:target-id`, `:target-pos`, `:threat`, `:safe-pos`,
  `:focus-pos`, `:area-id`

### Initial Vocabulary

The exact list can grow, but a compact starting set should cover most early
needs:

- `:alarm` — "something is wrong here"
- `:rally` — "group up / engage"
- `:warn` — "danger at this location / from this class"
- `:help` — "I am under threat"
- `:mark-target` — "this specific target matters"
- `:retreat` — "fall back / disengage"
- `:muster` — "move to this anchor / post"
- `:panic` — uncontrolled distress, useful for towns/crowds

### Example Signals

```clojure
{:type :alarm
 :from :shopkeeper-2
 :pos [30 11]
 :channel :sound
 :radius 10
 :threat :theft}

{:type :rally
 :from :dog-1
 :pos [14 7]
 :channel :sound
 :focus-pos [15 8]
 :target-id :player}

{:type :retreat
 :from :villager-4
 :pos [22 5]
 :channel :visual
 :safe-pos [18 3]}
```

### Blackboard Interaction

Signals should be just another normalized event source feeding blackboard
reconstruction.

Examples:

- `:mark-target` may refresh `:last-seen-target` without direct LOS
- `:help` may raise `:ally-distress`
- `:alarm` may raise local `:threat-level`
- `:muster` may override routine drift and strengthen `:return-home`/`:guard`

Traits and memory channels can control who is receptive to which signals.

### Renderer Split

Signal semantics should not dictate one presentation. Renderers map the same
signal type onto faction/species-specific player-facing cues.

Examples:

- dogs: bark / howl / growl
- hunter: whistle / shout / hand signal
- villagers: scream / point / bell
- constructs: siren / beacon flash / rune pulse
- substrate creatures: glitch chirp / bracket flare / state echo

Possible player-facing renderers:

- log text
- local sound text
- VFX pulse or flare
- overhead icon
- map ping / beacon
- ambient hint only when partially perceived

This gives one gameplay vocabulary with many expressive skins.

## Worked Example: Territorial Troll

This is the solitary, non-social stress test. The troll should feel rooted in a
place, not like a generic pursuer.

### Shared Event Vocabulary

- `:saw-target`
- `:lost-target`
- `:heard-disturbance`
- `:took-damage`
- `:intrusion`
- `:target-left-territory`

### Troll Blackboard

```clojure
{:home-pos ...
 :territory-radius 8
 :last-seen-target ...
 :recent-damage ...
 :intrusion-level ...
 :target-in-territory? ...
 :safe-route? ...}
```

### Scoring Shape

Primary drives:

- `:guard-territory`
- `:attack-intruder`
- `:pursue-briefly`
- `:self-preserve`
- `:return-home`

Trait or corruption modifiers could shift the shape:

- `:territorial` increases `:guard-territory` and reduces chase range
- `:berserker` suppresses retreat
- a corruption may make the troll overextend or ignore home bounds

### Resulting Behavior

- If the player enters the troll's territory, `:intrusion` raises local threat
  even before melee range.
- Inside territory, `:attack-intruder` dominates.
- Outside territory, the troll may pursue briefly, but `:return-home` rises
  sharply once the target escapes the defended area.
- If wounded, the troll may fall back toward defensible terrain instead of
  chasing forever.

This gives a creature that teaches space ownership rather than pure aggression.

## Worked Example: Guard Post

This is the structured coordination case: multiple guards, a defended place,
alarm propagation, and role differentiation.

### Shared Event Vocabulary

- `:saw-stranger`
- `:alarm`
- `:mark-target`
- `:help`
- `:muster`
- `:door-broken`
- `:ally-died`

### Guard Blackboard

```clojure
{:post-pos ...
 :assigned-lane ...
 :last-seen-target ...
 :alarm-level ...
 :ally-distress ...
 :guard-order ...
 :fallback-pos ...}
```

### Role Split

Even within one faction, guards may differ:

- sentry: stronger `:watch` / `:raise-alarm`
- bruiser: stronger `:hold-line`
- runner: stronger `:summon-help`
- captain: stronger `:mark-target` / `:muster`

### Scoring Shape

Primary drives:

- `:watch`
- `:challenge-stranger`
- `:raise-alarm`
- `:hold-post`
- `:pursue-intruder`
- `:protect-ally`
- `:muster-at-post`

### Resulting Behavior

- A sentry sees the player and emits `:alarm` plus `:mark-target`.
- Nearby guards reconstruct the signal and score `:muster-at-post` or
  `:hold-post` instead of all stampeding forward.
- One guard may peel off into `:summon-help`, while another remains to block the
  corridor.
- If a guard dies, `:ally-died` can spike aggression, panic, or fallback,
  depending on faction traits.

This produces readable defense behavior rather than a pile of independent melee
units.

## Worked Example: Turret Room

This is the machine-coordination case. It tests whether the model works when
communication is beacons and control channels instead of animal or humanoid
social cues.

### Shared Event Vocabulary

- `:sensor-trip`
- `:mark-target`
- `:alarm`
- `:muster`
- `:repair-request`
- `:line-blocked`
- `:power-loss`

### Turret Blackboard

```clojure
{:firing-lane ...
 :last-marked-target ...
 :line-clear? ...
 :overheat-level ...
 :support-nearby? ...}
```

### Keeper / Operator Blackboard

```clojure
{:assigned-room ...
 :active-turrets [...]
 :repair-priority ...
 :breach-pos ...
 :fallback-switch? ...}
```

### Scoring Shape

Turret drives:

- `:track-target`
- `:fire-lane`
- `:cease-fire` when blocked by allies or geometry
- `:protect-core`

Keeper drives:

- `:raise-alarm`
- `:repair-system`
- `:unblock-lane`
- `:retreat-to-control-point`
- `:rearm-defense`

### Resulting Behavior

- A sensor trip emits `:alarm` and `:mark-target`.
- Turrets that can perceive the signal but not yet see the target may pre-aim
  or hold lanes.
- A keeper can react to `:repair-request` or `:power-loss` while avoiding direct
  melee commitment.
- If an ally blocks a lane, the turret can score `:cease-fire` instead of
  friendly-firing blindly.

This demonstrates that the same communication vocabulary and blackboard model
can support mechanical defenses, not just creatures with ordinary social AI.

## Spell-System Integration

All important world effects in this AI model should be reachable through the
spell-casting system. AI coordination must not depend on hidden mechanics that
spells cannot touch.

### Design Constraint

Signals, alarms, beacons, panic effects, rally markers, territorial warnings,
and similar world-facing effects should be modeled as ordinary world events or
world effects that spells can:

- create
- suppress
- redirect
- amplify
- counterfeit
- consume

### Consequences

- A silence-like spell should be able to suppress sound-channel communication.
- An illusion spell should be able to create false disturbances or target marks.
- Fear or confusion effects should be able to alter panic / retreat signaling.
- Rune wards or machine hacks should be able to block or hijack beacon-style
  coordination.
- If an AI behavior depends on a perceivable effect in the world, that effect
  should be representable in spell/system terms rather than only inside AI code.

### Architectural Benefit

This keeps the game coherent:

- the AI and the player act on the same world substrate,
- late-game magic and substrate corruption can meaningfully interfere with group
  behavior,
- "communication" stays part of the simulation instead of becoming a special
  unspellable rules channel.
