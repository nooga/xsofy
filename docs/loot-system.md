# Loot System

## Depth Curves

Every item type has a gaussian frequency curve over dungeon depth:

```
effective_weight(depth) = base_weight * exp(-(depth - peak)^2 / (2 * spread^2))
```

- `peak` — depth where this item is most common
- `spread` — how wide the bell curve is (large = always available)
- `weight` — base frequency relative to other items

This creates overlapping depth windows: daggers fade as long swords ramp up, chain mail appears as leather becomes scarce. The player always sees a mix of familiar and new.

## Floor Budget

Each floor gets a point budget: `6 + depth + rand(3)`.

Items cost budget points. The generator picks weighted-random items until the budget runs out, with per-category caps (max 2 weapons, 2 armor, 3 potions, 2 scrolls, 3 ammo) to prevent flooding.

The loot bag is distributed across rooms: 0-2 items per room, prioritizing rooms further from the entrance.

## Enchantment

Weapons and armor roll an enchantment level at spawn:

```
enchant = clamp(-3, 5, rand(-1..+2) + floor(depth / 3))
```

- Depth 1: mostly -1 to +1
- Depth 6: mostly +1 to +3
- Enchantment scales damage (weapons) and defense (armor) via `1.28^enchant`
- Positive enchant also reduces strength requirement

## Cursed Items

Negative enchantment = cursed. A -1 sword or -2 armor **cannot be unequipped** until enchanted to +0 or higher.

- Scrolls of enchantment are the universal fix (+1 enchant per scroll)
- A -2 item needs 2 scrolls to uncurse
- Enchantment level is unknown until the item is equipped (the "identify gamble")
- Cursed runic items are especially dangerous — stuck with whatever the program does

This is Brogue's design: no separate curse flag, just the enchantment number line.

## Procedural Runics

Runic items have spell programs generated from templates with random slot fills. Not every weapon/armor is runic — chance scales with depth (15% at D1, 35% at D5+).

### Templates

**Weapon runics** (triggered on hit, target is the enemy):
- Simple: `[effect]` — fire, ice, push
- Parameterized: `[:apply effect N]` — (damage 3), (heal 2)
- Area: `[:apply :area N effect]` — area fire on hit
- Chain: `[:arc]` — chain lightning
- Combo: `[effect1 effect2]` — push + fire
- Vampiric: `[:apply :damage N :self :apply :heal M]` — life drain

**Armor runics** (triggered when hit):
- Simple: `[effect]` — push attacker away
- Area: `[:apply :area 1 effect]` — ice nova when struck

### Quality Tiers

- **Useful** (55%): simple, straightforward effects
- **Powerful** (25%): area, chain, vampiric — strong but fair
- **Dangerous** (15%): useful template with self-targeting mixed in
- **Broken** (5%): inverted — heals enemies, damages self

### Naming

Derived from the rune program: "flaming" (fire), "freezing" (ice), "thundering" (arc), "vampiric" (drain), "volatile" (dangerous), "cursed" (broken). Combos concatenate.

### Integration

Runic programs are stored as `:runes` on the item entity — the existing combat system already evaluates them via `spell/eval-spell` on hit. No combat changes needed.
