# Xs of Y

A roguelike where the magic system is lisp.

**[Play in your browser](https://nooga.github.io/xsofy/)**

![screenshot](xsofy.gif)

Runes are secretly s-expressions, randomized each run. The player has root access to the game's physics engine but the documentation is in a dead language that changes every boot.

Think Brogue meets Noita, written in ~5800 lines of [let-go](https://github.com/nooga/let-go) — a Clojure dialect running on a Go bytecode VM. No dependencies. Starts in 6ms.

## What works

- Dungeon generation with rooms, corridors, doors, and destructible terrain
- FOV, dynamic lighting from torches, fires, and spells
- Melee and ranged combat with bows, thrown weapons, and ammunition
- Enchanted weapons — vampiric blades, thunder hammers with chain lightning, flaming swords
- Creature AI with stealth, investigation, and fleeing behaviors
- Grass that burns, blood splatter proportional to damage, fire chain reactions
- Rune spell system where `[:fire]` on a dagger means something different than `[:fire]` on a scroll
- Rune codex for tracking known and unknown rune mappings
- Slimes that split when damaged
- WASM build — runs in the browser via xterm.js

## What doesn't work yet

The rune identification system (the core mystery mechanic), gas simulation, time travel spells, machine rooms, and probably several things that crash when you look at them funny.

## Running locally

```bash
lg main.lg
```

Get `lg` from [let-go](https://github.com/nooga/let-go). Or install it with Homebrew:

```bash
brew tap nooga/let-go https://github.com/nooga/let-go
brew install let-go
```

## Design

- [`design.md`](docs/design.md) — the concept
- [`spell-dsl.md`](docs/spell-dsl.md) — how rune spells compose
- [`world-systems.md`](docs/world-systems.md) — everything else
