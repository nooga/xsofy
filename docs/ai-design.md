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
