# Modpack Guide

What a modpack maker can change in AlchyRift without code: crafting recipes, the cost of growing rooms, the gates' shape and the floor a gate lays down. For the general methods (replacing a datapack file, removing a recipe, tags), see the [AlchyX Modpack Guide](https://github.com/JustAlex14/AlchyX/wiki/Modpack-Guide).

## Where each thing lives

| What | Where |
|---|---|
| Crafting recipes (18) | `data/alchyrift/recipe/<item>.json` |
| Gate shapes | `data/alchyrift/multiblock/rift_gate.json` and `greater_rift_gate.json` |
| Room growth costs | `config/alchyrift-common.toml`, section `rooms` |
| Gate floors per dimension | `config/alchyrift-common.toml`, section `gates` |
| Surge crystals | `config/alchyrift-common.toml`, section `conduits` |
| The rift dimension | `data/alchyrift/dimension/`, `dimension_type/`, `worldgen/` |

Datapack files go in a datapack or in `kubejs/data/`. Run `/reload` after editing them.

## Recipes

Every item is crafted with an ordinary crafting recipe named after the item: `rift_anchor.json`, `void_seed.json`, `item_conduit.json`, `surge_crystal.json` and so on. To change one, put a file with the same path in your datapack. For example, a cheaper Void Seed:

`data/alchyrift/recipe/void_seed.json`

```json
{
  "type": "minecraft:crafting_shaped",
  "category": "misc",
  "pattern": [ "VAV", "ARA", "VAV" ],
  "key": {
    "A": "minecraft:amethyst_shard",
    "R": "alchyrift:rift_eye",
    "V": "alchyrift:void_pearl"
  },
  "result": { "id": "alchyrift:void_seed", "count": 1 }
}
```

To remove a recipe, replace the file with `{"neoforge:conditions": [{"type": "neoforge:false"}]}`, or use KubeJS: `event.remove({ id: 'alchyrift:void_seed' })`.

Items worth gating in a progression:

| Item | Why it matters |
|---|---|
| `rift_anchor` | the core of the Rift Gate, so the way into the mod |
| `greater_rift_anchor` | the core of the Greater Rift Gate |
| `void_seed` | what growing a room costs by default |
| `rift_relay`, `greater_rift_relay`, `rift_port` | wireless links and lines through a gate |
| `surge_crystal` | multiplies what conduits move |

## Growing rooms

Three settings decide what a room costs to grow:

```toml
[rooms]
growItem = "alchyrift:void_seed"
growCosts = [1, 3, 6, 10, 15]
growExtras = ["", "minecraft:nether_star"]
maxRoomSize = 3
```

- `growItem` is the item every growth takes. It can be any item, from any mod.
- `growCosts` is how many of it each growth takes: the first number is for 1 x 1 to 2 x 2 chunks, the second for 2 x 2 to 3 x 3, and so on. Growths past the end of the list cost the last number.
- `growExtras` is one more item per growth, in the same order. `""` means nothing extra. The default asks for a nether star to go from 2 x 2 to 3 x 3.
- `maxRoomSize` caps the size, from 1 to 6 chunks along a side.

A pack that wants rooms to be a late reward could set `growItem` to a rare item and add a boss drop to `growExtras`:

```toml
growItem = "alchyrift:void_seed"
growCosts = [2, 4, 8, 16, 32]
growExtras = ["minecraft:echo_shard", "minecraft:nether_star", "minecraft:dragon_egg"]
maxRoomSize = 4
```

## Gate shapes

The two gates are AlchyX patterns, so a datapack can change their blocks or size: copy `rift_gate.json` from the jar to the same path in your pack and edit it. Keep the `core` (`alchyrift:rift_anchor`, or `alchyrift:greater_rift_anchor`), the stabilizer and the air cells where the portal opens, since the mod looks for them through the pattern's `anchors`. Everything else (the frame, the floor, what they turn into) is free to change. The format is on [Pattern Format](https://github.com/JustAlex14/AlchyX/wiki/Pattern-Format).

## Gate floors

A gate lays down the ground of the dimension it opens in. `floorBlocks` maps a dimension to a block:

```toml
floorBlocks = ["minecraft:overworld=minecraft:grass_block", "minecraft:the_nether=minecraft:netherrack", "minecraft:the_end=minecraft:end_stone", "alchyrift:rift_void=alchyrift:void_stone", "somemod:moon=somemod:moon_dust"]
```

Add a line for each modded dimension in the pack. A dimension that is not listed keeps its floor as it is. An empty list turns floors off.

## Shipping it

- Recipes and gate shapes are server data: ship them in KubeJS `data` or a global datapack.
- Put the edited `config/alchyrift-common.toml` in the pack's `config` folder. It is sent to players when they join, so their screens show the server's costs.
