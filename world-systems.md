# World Systems Specification

## World State

```clojure
{:terrain    (byte-array (* width height))  ;; tile type grid, mutable, rarely changes
 :width      80
 :height     50
 :effects    {[3 4] {:fire 5 :light 3}     ;; sparse map — cell → effect → intensity/ttl
              [5 6] {:water 1}}
 :entities   {:player   {:pos [10 20] :type :creature :glyph "@" ...}
              :goblin-1 {:pos [30 25] :type :creature ...}
              :sword-3  {:pos [10 20] :type :item :held-by :player ...}
              :trap-2   {:pos [22 18] :type :trap ...}}
 :lighting   (int-array (* width height))   ;; precomputed light values per cell
 :fov        #{[x y] ...}                   ;; player's visible cells this turn
 :turn       42
 :pending    []                              ;; delayed spells from wait/delay
 :precog     nil                             ;; precognition bookmark
 :rune-table {...}                           ;; per-run rune mappings
 :seed       12345                           ;; run seed
 :title      "Coils of Recursion"}           ;; generated per run
```

### Terrain Byte Encoding

Terrain is a flat byte-array indexed as `(+ x (* y width))`. Each byte is a tile type:

```clojure
(def tiles
  {0  :void              ;; out of bounds
   1  :floor-stone
   2  :floor-dirt
   3  :floor-grass       ;; flammable
   4  :floor-water-shallow
   5  :floor-water-deep
   6  :floor-lava
   7  :floor-ice         ;; melts into shallow water
   8  :wall-stone
   9  :wall-dirt
   10 :wall-granite      ;; indestructible
   11 :door-closed
   12 :door-open
   13 :door-wood-closed  ;; flammable, burns away
   14 :door-wood-open    ;; flammable
   15 :stairs-down
   16 :stairs-up
   17 :chasm             ;; can fall through, can throw things across
   18 :bridge-stone
   19 :bridge-wood       ;; flammable — burns away, becomes chasm
   20 :floor-glass       ;; transparent, breakable → becomes chasm
   21 :wall-wood         ;; flammable — burns away → floor
   22 :floor-carpet      ;; flammable, over stone floor
   23 :grate             ;; gas/water passes through, blocks movement
   24 :pressure-plate    ;; triggers connected trap
   25 :altar
   26 :floor-bog         ;; produces swamp gas, slows movement
   27 :floor-moss-stone  ;; flammable surface on stone
   28 :pillar            ;; blocks movement + FOV, indestructible, looks nice})

;; tile properties (derived, not stored)
(def tile-props
  {:floor-stone    {:walkable true  :transparent true  :flammable false :destructible true}
   :wall-stone     {:walkable false :transparent false :flammable false :destructible true}
   :wall-granite   {:walkable false :transparent false :flammable false :destructible false}
   :wall-wood      {:walkable false :transparent false :flammable true  :destructible true}
   :floor-water-shallow {:walkable true :transparent true :conducts-electricity true}
   :floor-lava     {:walkable false :transparent true :flammable false :emits-light 3}
   :chasm          {:walkable false :transparent true :flammable false}
   :floor-glass    {:walkable true  :transparent true :flammable false :destructible true}
   :grate          {:walkable false :transparent true :gas-permeable true :liquid-permeable true}
   ...})
```

Terrain is mutable because it rarely changes and doesn't need history granularity — when it does change (wall destroyed, door opened), we snapshot the whole array. Structural sharing doesn't help with flat arrays so we keep mutations rare.

---

## Terrain Generation

### Strategy: Hybrid Rooms + Caves

Like Brogue: mix of rectangular rooms connected by corridors, with organic cave sections. Each dungeon level is one of several templates.

### Level Templates

| Template       | Description                                  | Frequency |
|----------------|----------------------------------------------|-----------|
| `:rooms`       | BSP rectangular rooms + corridors            | common    |
| `:caves`       | cellular automata organic caves              | common    |
| `:hybrid`      | rooms connected by cave passages             | common    |
| `:chasm`       | rooms around a central chasm, bridges        | rare      |
| `:flooded`     | partially flooded level, shallow/deep water  | rare      |
| `:machine`     | NOT a biome — machines are placed INTO any biome | —      |

### Generation Steps

```
1. Choose template based on depth + RNG
2. Carve base layout:
   - BSP: recursively split rect, place rooms in leaves, connect with corridors
   - Caves: fill grid randomly ~45% wall, run CA (B5678/S45678) for 4-5 steps
   - Hybrid: BSP for rooms, CA for connecting passages
3. Place features:
   - Doors at room/corridor junctions
   - Stairs (one up, one down, placed far apart)
   - Water/lava pools (flood fill from seed points)
   - Chasms (remove floor in connected regions)
4. Place machines (see Machine Rooms below)
5. Place traps
6. Place items
7. Place creatures
8. Compute initial lighting
```

### BSP Room Generation

```clojure
(defn generate-bsp [width height min-room-size]
  ;; 1. Start with full rect
  ;; 2. Recursively split (alternating H/V) until leaves are small enough
  ;; 3. Place a room (slightly smaller than leaf) in each leaf
  ;; 4. Connect sibling rooms with corridors (L-shaped or straight)
  ;; Returns: {:rooms [rect...] :corridors [[x y]...] :doors [[x y]...]})
```

### Cellular Automata Caves

```clojure
(defn generate-caves [width height]
  ;; 1. Random fill: each cell 45% chance wall
  ;; 2. Run 4-5 iterations of B5678/S45678
  ;; 3. Flood-fill to find connected regions
  ;; 4. Keep largest region, fill others with wall
  ;; 5. Ensure connectivity with tunnels if needed)
```

---

## Field of View

### Algorithm: Recursive Shadowcasting

Standard roguelike FOV. 8-octant symmetric shadowcasting. Produces a set of visible cells from the player's position.

```clojure
(defn compute-fov [terrain pos radius]
  ;; returns #{[x y] ...} of visible cells
  ;; uses recursive shadowcasting per octant
  ;; respects :transparent tile property
  ;; radius is sight range (affected by lighting, status effects)
  )
```

### FOV Interacts With

- **Lighting** — cells outside FOV are not visible regardless of light
- **Memory** — previously seen cells render as dimmed (remembered terrain, no entities)
- **Glass** — `:floor-glass` and `:wall-glass` are transparent, FOV passes through
- **Gas** — dense gas in a cell may reduce transparency (thick smoke blocks FOV)
- **Spells** — `all-visible` selector uses FOV set, `nearest` searches within FOV

### Memory Map

```clojure
;; stored on player entity
{:memory {[10 20] :floor-stone    ;; last seen tile type
          [10 21] :wall-stone
          ...}}
```

Updated each turn with currently visible cells. Rendered dimmed/gray when outside current FOV.

---

## Lighting

### Model: Per-Cell Light Color + Intensity

Like Brogue, lighting is colored and atmospheric. Each cell has an RGB light value computed from nearby light sources.

```clojure
;; light sources:
;; - torches on walls (warm orange, radius 5)
;; - lava (red-orange, radius 3)
;; - glowing fungi (green, radius 2)
;; - player's torch/lantern (warm white, radius 8)
;; - fire effects (orange-red, radius 4, flickers)
;; - ice effects (pale blue, radius 1)
;; - magic effects (various colors)
;; - sunlight shafts from above (white, certain cells)
```

### Light Computation

```clojure
(defn compute-lighting [terrain effects entities]
  ;; 1. Collect all light sources (terrain features, effects, entities with lights)
  ;; 2. For each source, cast light rays (using same shadowcasting as FOV)
  ;; 3. Accumulate RGB light at each cell (additive, clamped)
  ;; 4. Apply ambient minimum so fully dark cells are still barely visible to player
  ;; Returns: array of [r g b] per cell
  )
```

Light is recomputed when sources change (fire spreads, torch picked up, spell cast). Between those events the lighting array is cached.

### Darkness as Gameplay

- Unlit areas: player can't see far, creatures can ambush
- Light sources attract creatures
- Fire spells illuminate the area (side benefit)
- Ice spells dim nearby light (side effect)
- Destroying a light source plunges area into darkness

---

## Physics: Effects System

### Effect Types

Effects live in the sparse `:effects` map. Each cell maps to a set of active effects with intensity/TTL:

```clojure
{[3 4] {:fire {:intensity 3 :ttl 8}
        :light {:intensity 4 :color [255 120 30]}}
 [7 2] {:gas {:type :flammable :density 5}
        :light {:intensity 1 :color [100 200 100]}}
 [5 6] {:water {:depth 2}}}
```

### Effect Definitions

#### Gases (diffuse, interact with fire/water)

| Gas                | Color          | Behavior                                            |
|--------------------|----------------|-----------------------------------------------------|
| `:methane`         | brown haze     | flammable — ignites into explosion, produced by swamps/bogs |
| `:swamp-gas`       | green haze     | flammable + poison, natural in swamp levels         |
| `:poison-gas`      | purple haze    | damages creatures breathing it, lingers             |
| `:confusion-gas`   | pink haze      | confuses creatures (randomize movement)             |
| `:paralysis-gas`   | yellow haze    | paralyzes creatures (skip turns)                    |
| `:steam`           | white haze     | blocks FOV, produced by fire+water, harmless, rises and dissipates |
| `:smoke`           | dark gray haze | blocks FOV, produced by burning wood/fungus         |

All gases share behavior: diffuse to neighbors each tick (density averages with adjacent cells), drift upward (density decreases over time), pass through grates, blocked by walls/closed doors.

#### Liquids (flow, pool, interact with terrain)

| Liquid             | Color          | Behavior                                            |
|--------------------|----------------|-----------------------------------------------------|
| `:water`           | blue           | flows to adjacent lower/equal cells, extinguishes fire, conducts electricity, deep water drowns |
| `:mud`             | brown          | shallow liquid, slows movement, doesn't flow far    |
| `:blood`           | dark red       | cosmetic mostly, left by wounded, makes floor slippery |
| `:acid`            | bright green   | damages entities and items, slowly dissolves terrain (stone→rubble), doesn't spread far |
| `:lava`            | orange-red     | extreme damage, emits strong light, ignites everything, evaporates water into steam, very slow flow |
| `:oil`             | dark yellow    | flammable liquid — fire spreads across oil instantly, coats entities (they become flammable) |

Liquids flow based on depth equalization with neighbors. Deep cells flow to shallow/empty adjacent cells. Blocked by walls, passes through grates.

#### Fire & Energy

| Effect             | Properties        | Behavior                                        |
|--------------------|-------------------|-------------------------------------------------|
| `:fire`            | intensity, ttl    | damages entities, spreads to flammable surfaces/materials, emits light, evaporates water→steam, ignites gas/oil |
| `:embers`          | ttl               | dying fire, dim light, can reignite with fuel   |
| `:ice`             | integrity         | blocks movement, melts from fire, freezes water on contact, extinguishes fire |
| `:electricity`     | intensity, ttl    | damages entities, chains through water (all connected water cells), arcs to metal/conductive entities |

#### Surface Features (on terrain, not flowing)

| Feature            | Properties        | Behavior                                        |
|--------------------|-------------------|-------------------------------------------------|
| `:fungus`          | growth            | flammable ground cover, spreads slowly to adjacent floor, glows faintly (green light), burns into smoke |
| `:web`             | integrity         | blocks movement, flammable (burns fast, produces fire), spiders ignore |
| `:moss`            | growth            | cosmetic, flammable, grows on stone walls        |
| `:creep`           | growth, type      | magical ground cover, various effects when stepped on |

### Cellular Automata Step

Each turn, effects update via CA rules. This produces animation frames.

```clojure
(defn step-effects [world]
  ;; Returns lazy seq of world states (one per CA sub-tick)
  ;;
  ;; For each sub-tick:
  ;; 1. Fire: spread to adjacent flammable cells (probabilistic), reduce ttl, emit light
  ;; 2. Gas: diffuse density to neighbors (average with adjacent), drift upward
  ;; 3. Water: flow to adjacent lower cells, equalize depth
  ;; 4. Steam: rise (reduce ttl), block FOV
  ;; 5. Interactions:
  ;;    - fire + gas(flammable) → explosion (fire in radius, destroy gas)
  ;;    - fire + water → steam + extinguish fire
  ;;    - fire + ice → melt ice, reduce fire
  ;;    - electricity + water → electrify all connected water cells
  ;;    - acid + terrain → degrade terrain (stone→rubite→floor eventually)
  ;; 6. Remove expired effects (ttl <= 0, density <= 0)
  ;; 7. Damage entities standing in harmful effects
  )
```

### Interaction Matrix

```
             fire        water      ice       electric   acid      oil
fire         —           steam      melt      —          —         INFERNO
water        steam       —          freeze    conduct    dilute    separate
ice          melt        freeze     —         —          melt      —
electric     —           conduct    —         —          —         —
acid         —           dilute     melt      —          —         —
oil          INFERNO     separate   —         —          —         —

             methane     swamp-gas  poison-g  confuse-g  fungus    web
fire         EXPLODE     EXPLODE    burn-off  burn-off   smoke+fire fire
water        absorb      absorb     absorb    absorb     —         —
electric     EXPLODE     EXPLODE    —         —          —         —
```

Key interactions:
- **fire + flammable gas** → EXPLODE: gas consumed, fire fills radius proportional to gas density
- **fire + oil** → INFERNO: fire spreads instantly across all connected oil cells
- **fire + water** → both consumed, steam produced
- **fire + ice** → ice melts (becomes water), fire weakened
- **fire + fungus** → fungus burns, produces smoke + fire spreads along fungus trail
- **fire + web** → web burns instantly, fire produced
- **electricity + water** → all connected water cells become electrified (area damage)
- **electricity + flammable gas** → EXPLODE (spark ignition)
- **acid + terrain** → slow degradation (stone wall → rubble after several turns)
- **water + acid** → dilute (both weakened)
- **oil + water** → oil floats, doesn't mix (oil layer on top)

### Chain Reactions

Chain reactions are the emergent fun. The CA step keeps running sub-ticks as long as new interactions trigger:

```clojure
(defn step-effects-until-stable [world max-ticks]
  ;; Run CA sub-ticks, emitting frames, until no new interactions
  ;; or max-ticks reached (safety valve for infinite chains)
  )
```

A player casts fire into a gas-filled room: fire ignites gas → explosion spreads fire → fire hits more gas → bigger explosion → fire reaches water → steam fills room blocking FOV. Each sub-tick is an animation frame. This is the Brogue spectacle.

---

## Destructible Terrain

Terrain can be changed by:

| Cause           | Effect                                        |
|-----------------|-----------------------------------------------|
| Explosion       | wall-stone → rubble (floor-stone + debris)    |
| Acid            | wall-stone degrades over turns                |
| Fire            | wall-wood burns away → floor                  |
| Ice             | water freezes → floor-ice (walkable)          |
| Transmute spell | any tile → any tile                           |
| Destroy spell   | wall → floor, floor → chasm                   |
| Earthquake      | random walls → rubble in radius               |

When terrain changes:
1. Mutate the byte-array
2. Recompute FOV (walls removed = new sightlines)
3. Recompute lighting (light can now pass through)
4. Check structural integrity (optional: unsupported ceiling collapses?)

---

## Entities

Everything that isn't terrain is an entity. Entities live in the persistent `:entities` map keyed by unique ID.

### Entity Types

#### Creatures

```clojure
{:id       :goblin-1
 :type     :creature
 :species  :goblin
 :pos      [30 25]
 :glyph    "g"
 :color    [0 200 0]
 :hp       8
 :max-hp   8
 :mind     {:known-runes {} :memory {} :identity :goblin-1}
 :body     {:strength 3 :dexterity 4 :armor 0}
 :ai       {:state :patrol :home [30 25] :path nil :alert 0}
 :spells   [[:target (damage 2)]]           ;; melee attack
 :drops    [:gold :short-sword]
 :statuses {}}
```

#### Items

```clojure
{:id       :sword-3
 :type     :item
 :subtype  :weapon
 :pos      [10 20]       ;; nil if in inventory
 :held-by  :player       ;; nil if on floor
 :glyph    "/"
 :color    [200 200 200]
 :name     "iron sword"
 :spell    [(damage 3)]  ;; the spell it casts when used
 :runes    [ᚦ ᚨ]        ;; visible runes inscribed (garbled until identified)
 :properties {:flammable false :conductive true :weight 3}}
```

#### Traps

```clojure
{:id        :trap-2
 :type      :trap
 :subtype   :fire-trap
 :pos       [22 18]
 :glyph     "^"          ;; hidden until detected
 :hidden    true
 :spell     [self (area 2) fire (damage 3)]  ;; triggered as spell, self = trap position
 :reusable  true}
```

#### Doors

```clojure
{:id     :door-7
 :type   :door
 :pos    [15 20]
 :glyph  "+"
 :state  :closed         ;; :open, :closed, :locked, :broken
 :mind   nil             ;; nil unless someone mind-swapped into it
 :hp     10}
```

#### Containers

```clojure
{:id       :chest-1
 :type     :container
 :pos      [20 15]
 :glyph    "="
 :state    :closed
 :locked   true
 :contents [:potion-4 :scroll-2]
 :hp       5}
```

### Item Categories

| Category    | Used how          | Spell context default  |
|-------------|-------------------|------------------------|
| `:weapon`   | melee/ranged      | melee target or projectile |
| `:armor`    | passive           | self (on-hit trigger)  |
| `:potion`   | drink             | self                   |
| `:scroll`   | read              | self                   |
| `:wand`     | aim               | aimed cell             |
| `:rune-stone` | study           | identifies a rune      |
| `:food`     | eat               | self                   |
| `:gold`     | —                 | currency               |
| `:tool`     | use               | varies                 |

### Inventory

Player has a flat inventory list on the entity. Items in inventory are entity IDs. Equipment slots are separate:

```clojure
;; on the player entity:
{:inventory [:sword-3 :potion-1 :scroll-5 :food-2 :rune-stone-1]
 :equipment {:weapon :sword-3
             :armor  :chainmail-1
             :ring   nil
             :light  :torch-4}
 :inventory-limit 20}

;; items still exist in :entities but with :pos nil
;; dropping an item: remove from :inventory, set :pos to player's pos
;; picking up: add to :inventory, set :pos to nil
```

Equipment slots:
- `:weapon` — melee or ranged, provides attack spell
- `:armor` — damage reduction, may have on-hit spell
- `:ring` — passive effect (spell that triggers each turn on self)
- `:light` — torch, lantern, glowing orb — determines player's light radius

No nested containers for now. Chests are entities on the floor that dump contents when opened.

---

## Machine Rooms (Brogue-style)

Pre-authored contraption templates placed during level generation. Each machine is a small self-contained puzzle or trap.

### Machine Definition

```clojure
{:name     "gas chamber"
 :min-depth 3
 :rooms    [{:size [6 4] :terrain :floor-stone :fill {:gas {:type :flammable :density 3}}}]
 :entities [{:type :trap :subtype :pressure-plate :pos :relative [2 2]
             :spell [self fire]}                     ;; stepping on it ignites the room
            {:type :item :subtype :scroll :pos :relative [4 3]}]  ;; bait
 :doors    [{:pos :entrance :state :open}]}

{:name     "flooding room"
 :min-depth 5
 :rooms    [{:size [8 6] :terrain :floor-stone}
            {:size [8 6] :terrain :floor-water-deep :relative :above :separated-by :grate}]
 :entities [{:type :trap :subtype :pressure-plate :pos :relative [4 3]
             :spell [target destroy]}]               ;; destroys the grate, water floods in
 :doors    [{:pos :entrance :state :open}
            {:pos :exit :state :locked}]}            ;; must solve to proceed

{:name     "lightning corridor"
 :min-depth 7
 :rooms    [{:size [12 3] :terrain :floor-water-shallow}]
 :entities [{:type :trap :subtype :wire-trap :pos :relative [0 1]
             :spell [(area 1) electricity]}           ;; electrifies the water
            {:type :item :subtype :boots-rubber :pos :relative [11 1]}]  ;; reward: immunity
 :doors    [{:pos :entrance :state :open}
            {:pos :exit :state :open}]}
```

### Machine Placement

Machines are not a biome — they're placed into any level template (rooms, caves, hybrid, etc.). A cave level can have a gas chamber carved into it. A flooded level can have a lightning corridor.

```
1. Generate base level layout (any biome)
2. Select 1-3 machines valid for current depth
3. Find suitable locations within the existing layout (enough space, accessible)
4. Carve/modify terrain for machine rooms
5. Place machine entities (traps, bait items, triggers)
6. Connect to existing passages if needed
```

---

## AI

### State Machine

Each creature has an AI state that determines behavior:

```
         ┌──────────┐
    ┌───→│  SLEEP   │
    │    └────┬─────┘
    │    noise/sight
    │    ┌────▼─────┐
    │    │  ALERT   │──no threat──→ back to sleep/patrol
    │    └────┬─────┘
    │    confirmed threat
    │    ┌────▼─────┐         out of range
    ├────│  HUNT    │◄────────────────┐
    │    └────┬─────┘                 │
    │    in range                     │
    │    ┌────▼─────┐   target moves  │
    │    │  FIGHT   ├─────────────────┘
    │    └────┬─────┘
    │    low HP
    │    ┌────▼─────┐
    └────│  FLEE    │
         └──────────┘
```

```clojure
(defn ai-turn [world entity]
  ;; Returns a spell to cast (everything is a spell)
  (case (get-in entity [:ai :state])
    :sleep   nil                                          ;; do nothing
    :alert   (ai-look-around world entity)                ;; scan for threats
    :patrol  (ai-move-toward world entity (:home (:ai entity)))  ;; wander
    :hunt    (ai-pathfind-to world entity target)         ;; approach
    :fight   (ai-pick-attack world entity target)         ;; choose spell
    :flee    (ai-flee-from world entity target)))         ;; run away
```

### AI Actions as Spells

Every creature action goes through the spell pipeline. But AI decision-making — *which* spell to cast, *when*, *at whom* — is custom engine code per species or behavior archetype.

```clojure
;; creature definition includes a spell list:
{:spells {:melee   [target (damage 2)]
          :ranged  [target projectile (damage 1)]
          :special [target (area 2) fire (damage 3)]}}

;; AI picks which spell based on custom logic:
(defmulti ai-pick-action (fn [world entity] (:species entity)))

(defmethod ai-pick-action :goblin [world entity]
  ;; goblins: melee if adjacent, throw rock if in range, flee if low HP
  (let [dist (distance-to-player world entity)]
    (cond
      (< (:hp entity) 3)         {:action :flee}
      (= dist 1)                 {:action :cast :spell (:melee (:spells entity))}
      (< dist 6)                 {:action :cast :spell (:ranged (:spells entity))}
      :else                      {:action :hunt})))

(defmethod ai-pick-action :dragon [world entity]
  ;; dragons: breath weapon when player is in line, melee if adjacent
  ;; custom: won't use breath if it would hit other dragons
  (let [dist (distance-to-player world entity)
        breath-path (line-of-cells (:pos entity) player-pos)
        friendlies-in-path (any-allies-in? world entity breath-path)]
    (cond
      (and (< dist 8) (not friendlies-in-path))
        {:action :cast :spell (:special (:spells entity))}
      (= dist 1)
        {:action :cast :spell (:melee (:spells entity))}
      :else
        {:action :hunt})))

;; the spell itself always goes through the same eval pipeline
;; AI just decides what to cast — engine handles the rest
```

This lets us write arbitrarily complex AI behavior (dragons avoiding friendly fire, mind flayers prioritizing unswapped targets, slimes splitting) while all *effects* still flow through the unified spell system.

### Pathfinding

A* on the terrain grid. Costs:

| Tile               | Cost  | Notes                        |
|--------------------|-------|------------------------------|
| Floor (any)        | 1     | base cost                    |
| Water (shallow)    | 2     | most creatures avoid         |
| Door (closed)      | 2     | can open                     |
| Fire/lava/acid     | 10+   | almost never path through    |
| Wall               | ∞     | impassable                   |
| Other creature     | 5     | prefer to go around          |

Flying/swimming creatures have different cost tables.

### Awareness

- **Sight**: creature has FOV just like player (smaller radius). Seeing the player or an ally die → alert.
- **Sound**: actions emit noise at a radius. Combat, explosions, doors = loud. Walking = quiet. Noise is checked against sleeping/patrolling creatures.
- **Smell**: optional — some creatures track by scent (blood trail).

```clojure
(defn emit-noise [world pos radius]
  ;; Wake up / alert creatures within radius
  (->> (entities-in-radius world pos radius)
       (filter #(#{:sleep :patrol} (get-in % [:ai :state])))
       (reduce #(update-entity %1 (:id %2) assoc-in [:ai :state] :alert) world)))
```

---

## Combat Model

There is no separate combat system. Combat is spells.

### Damage Resolution

```clojure
(defn apply-damage [world entity-id amount]
  (let [entity (get-entity world entity-id)
        armor  (get-in entity [:body :armor] 0)
        actual (max 0 (- amount armor))
        new-hp (- (:hp entity) actual)]
    (if (<= new-hp 0)
      (kill-entity world entity-id)
      (update-entity world entity-id assoc :hp new-hp))))
```

### Status Effects

Statuses are timed modifiers on entities:

```clojure
{:statuses {:poison {:ttl 5 :damage-per-turn 1}
            :frozen {:ttl 3 :skip-turns true}
            :confused {:ttl 4 :randomize-input true}
            :invisible {:ttl 10}
            :haste {:ttl 5 :extra-turn true}}}
```

Processed at start of entity's turn:
- `:poison` → take damage
- `:frozen` → skip turn
- `:confused` → random movement direction
- `:haste` → act twice
- Tick down TTL, remove at 0

### Death

```clojure
(defn kill-entity [world entity-id]
  (let [entity (get-entity world entity-id)
        pos (:pos entity)]
    (-> world
        (drop-inventory entity-id pos)     ;; items fall to floor
        (add-effect pos {:blood {:age 0}}) ;; cosmetic
        (remove-entity entity-id)
        (emit-noise pos 8))))              ;; death scream alerts nearby
```

### Melee

Player bumps into creature → equipped weapon's spell fires with default context = adjacent target. No weapon → unarmed spell `[(damage 1)]`.

### Ranged

Player aims weapon → equipped ranged weapon's spell fires with default context = projectile to aimed cell. `[target projectile (damage N)]` with projectile animating cell by cell and stopping on first entity hit.

---

## Creature Bestiary (Core Set)

| Species      | Glyph | HP  | Behavior     | Attack spell                     | Special |
|--------------|-------|-----|--------------|----------------------------------|---------|
| rat          | r     | 3   | patrol/flee  | `[(damage 1)]`                   | packs   |
| goblin       | g     | 8   | hunt         | `[(damage 2)]`                   | —       |
| archer       | a     | 6   | hunt(ranged) | `[target projectile (damage 2)]` | keeps distance |
| slime        | s     | 12  | hunt(slow)   | `[(damage 1) poison 2]`          | splits on hit |
| fire imp     | i     | 5   | fight        | `[target fire (damage 2)]`       | immune to fire |
| troll        | T     | 20  | hunt         | `[(damage 4)]`                   | regenerates |
| wraith       | W     | 10  | hunt         | `[(damage 2) (take :life)]`      | drains life |
| mind flayer  | M     | 15  | hunt         | `[nearest (take :mind)]`         | mind swap! |
| dragon       | D     | 40  | guard        | `[target projectile (area 2) fire (damage 5)]` | breath weapon |
| mimic        | =     | 15  | sleep→fight  | `[(damage 3)]`                   | looks like chest |
| gas spore    | o     | 1   | patrol       | death: `[self (area 3) gas]`     | explodes into gas |
| electric eel | e     | 6   | patrol(water)| `[(damage 2) electricity]`       | in water = area shock |

---

## Item Generation

### Rune Inscription

Every item with a magical effect has runes inscribed. The player sees garbled runes until identified:

```
You find a wand inscribed with: thryn-vorth-kael
```

Once `thryn` = `target`, `vorth` = `fire`, `kael` = `area 2` are identified:

```
You find a wand of fireball [target fire area 2]
```

Mundane items (plain sword, food) have no runes.

### Item Templates

```clojure
;; generated per-run with random rune combinations
{:weapon  [{:name "dagger"  :base-spell [(damage 2)] :rune-slots 1}
           {:name "sword"   :base-spell [(damage 3)] :rune-slots 2}
           {:name "bow"     :base-spell [target projectile (damage 2)] :rune-slots 1}]
 :potion  [{:spell [self (heal 5)] :rune-slots 0}       ;; potions are pre-made
           {:spell [self (cure-all)] :rune-slots 0}]
 :scroll  [{:rune-slots 3}]                              ;; scrolls are random rune combos
 :wand    [{:rune-slots 3 :charges 5}]}                  ;; wands are random too
```

Scrolls and wands get random rune combinations — some are powerful, some are useless, some are suicidal. The rune slots on weapons allow enchantment: find a rune stone, inscribe it on your sword. A sword with `fire` inscribed does `[(damage 3) fire]` on hit.

---

## Dungeon Depth Progression

| Depth | New elements                                    |
|-------|-------------------------------------------------|
| 1     | rats, goblins, basic items, simple rooms        |
| 2-3   | archers, slimes, first traps, first gas rooms   |
| 4-5   | fire imps, trolls, water levels, machine rooms  |
| 6-7   | wraiths, mind flayers, complex machines          |
| 8-9   | dragons, electric eels, multi-element puzzles    |
| 10    | final level — boss encounter using the rune system |

The player's power comes from two axes:
1. **Rune knowledge** — identifying runes, crafting spells, inscribing weapons
2. **Stat potions** — permanent upgrades found in the dungeon

### Stat Potions

No XP or leveling. The single permanent upgrade is **Potion of Strength**: +1 strength, +1 max HP, full heal. Strength is the universal power stat — more damage, more HP, harder to kill. Simple.

These are rare (1-2 per floor) and unidentified like everything else. The player might drink one thinking it's a healing potion and get a pleasant surprise — or they might save what they think is a strength potion and it turns out to be poison.

Strength potions are the main reason to push deeper. A cautious player who collects them all becomes powerful enough to survive the lower floors.

### Scrolls

Brogue-style consumable scrolls. Unidentified until used or identified by another scroll:

| Scroll                 | Effect                                                |
|------------------------|-------------------------------------------------------|
| Scroll of Identify     | reveal one rune mapping OR identify one item          |
| Scroll of Enchantment  | +1 rune slot on target item                           |
| Scroll of Teleportation| random teleport (self, default context)               |
| Scroll of Remove Curse | remove negative rune inscriptions from equipped item  |
| Scroll of Mapping      | reveal current floor layout                           |
| Scroll of Sanctuary    | create a temporary safe zone around self              |

Scrolls of Identify are the primary way to learn rune mappings safely. Finding one is more valuable than most weapons — knowledge compounds.

### Player Stats

```clojure
{:body {:strength 3       ;; base damage, max HP scales with this, carry limit
        :armor 0}         ;; from equipment only
 :hp 15
 :max-hp 15}             ;; max-hp = 12 + (strength * 3)
```

Strength affects the `damage` primitive's base value. A dagger with `[(damage 2)]` actually does `(+ 2 (floor (/ strength 2)))` damage. Max HP grows with strength too — one stat to rule them all.

Deeper floors = more creatures using the rune system against you.
