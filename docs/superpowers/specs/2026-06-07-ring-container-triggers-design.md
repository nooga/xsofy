# Ring Container Triggers Design

**Date:** 2026-06-07
**Status:** Approved for implementation
**Scope:** Minimal container-trigger framework with rings as the first supported container

## Goal

Introduce a unified trigger layer for spell-bearing containers, and use it to make equipped rings fire rune programs once on every replayed player turn.

## Why This Shape

The existing docs already point toward a shared container-trigger system rather than item-specific patches:

- `Weapon` on-hit is live
- `Ring` on-turn is a gap
- `Armor` on-take-hit is a gap

The clean fix is to separate:

1. **When** a container should fire
2. **Which** equipped item provides the rune program
3. **How** the existing spell evaluator runs it

That lets rings land now without baking ring-only logic into the runtime.

## First Slice

Only implement:

- trigger event: `:on-turn`
- container slot: `:ring`
- owner: `:player`

Do not implement armor, shield, wand, or creature-container generalization in this slice.

## Behavior

- If the player has an equipped ring with a non-empty `:runes` program, it fires once per replayed player turn.
- It fires on all replayed player turns, including:
  - movement
  - `:wait`
  - item use
  - stairs / floor transitions
- It does not fire on UI-only actions.
- It should see the **post-action** world state for that player turn.
- It targets the player’s current position by default.

## Runtime Placement

The trigger should run inside the deterministic world-turn pipeline, after the player action mutates the world and before the rest of the turn’s downstream simulation continues.

For the current codebase, that means:

- player action resolves in `world/update-world`
- if the action advanced `:turn`, run `:on-turn` container triggers
- then continue with the existing per-turn follow-up phases

This keeps the effect part of the same replayable action result.

## Architecture

Add a small trigger module that answers:

- for event `:on-turn`, which equipped containers should fire?
- given a container, how do we evaluate its rune program?

The spell layer should accept optional container metadata in the evaluation context, even if no current rune reads it yet. That preserves the “container-aware runes” direction without requiring new rune semantics in this PR.

## Data / API Direction

Minimal new trigger API:

```clojure
(trigger/fire-container-event world :player :on-turn)
```

Responsibilities:

- inspect the player’s equipped container for the relevant slot
- if it has runes, evaluate them through the existing spell path
- merge spell effects back into the world with the existing error/fizzle handling

## Testing

Add focused tests for:

- equipped ring with runes fires on `:wait`
- equipped ring with runes fires on movement
- equipped ring does not fire for UI actions
- unequipped ring does not fire
- replay reproduces ring-triggered turns exactly
- existing weapon rune behavior remains unchanged

## Out of Scope

- armor `:on-take-hit`
- shield `:on-block`
- wand/staff `:on-use`
- multiple ring slots or stacking policy
- new container-aware rune semantics beyond passing metadata through context
