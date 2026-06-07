# Ring Container Triggers Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a minimal container-trigger framework and make equipped rings fire runes once on every replayed player turn.

**Architecture:** Introduce a small trigger module that dispatches equipped-container rune programs by event. In this slice, only `:ring` on `:on-turn` is implemented, and `world/update-world` invokes it after a replayed player action advances `:turn`.

**Tech Stack:** let-go / `.lg`, existing `spell/eval-spell`, deterministic dispatch/replay tests

---

### Task 1: Add failing tests for ring trigger behavior

**Files:**
- Create: `xsofy/test/container_trigger_test.lg`
- Modify: `xsofy/test/run.lg`

- [ ] Write tests for ring firing on `:wait` and movement
- [ ] Write tests for no trigger on UI actions and unequipped rings
- [ ] Write a replay determinism test for ring-triggered turns
- [ ] Run the focused tests and confirm they fail

### Task 2: Add a minimal trigger module

**Files:**
- Create: `xsofy/container_trigger.lg`
- Modify: `xsofy/spell.lg`

- [ ] Add optional container metadata threading in spell context
- [ ] Add trigger helpers for equipped container lookup and event firing
- [ ] Keep the module generic in shape, but only implement `:ring` + `:on-turn`
- [ ] Run focused tests and confirm partial progress

### Task 3: Wire ring triggers into the player turn pipeline

**Files:**
- Modify: `xsofy/world.lg`

- [ ] Invoke container triggers after replayed player actions that advance `:turn`
- [ ] Ensure UI-only actions do not trigger rings
- [ ] Preserve existing downstream turn phases and replay determinism
- [ ] Run focused tests and confirm they pass

### Task 4: Add a concrete ring item for tests

**Files:**
- Modify: `xsofy/items.lg`

- [ ] Add a simple ring template usable by tests
- [ ] Keep loot/distribution changes out of scope unless required by tests
- [ ] Re-run focused tests

### Task 5: Run verification

**Files:**
- Test: `xsofy/test/container_trigger_test.lg`
- Test: `xsofy/test/run.lg`

- [ ] Run the focused container-trigger tests
- [ ] Run the full test suite
- [ ] Confirm no regression to existing weapon rune behavior
