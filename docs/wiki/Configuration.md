# Configuration

## Common

`config/alchyrift-common.toml`

| Section | Setting | Default | Meaning |
|---|---|---|---|
| `rooms` | `maxLoadedRooms` | -1 | how many rooms stay loaded with nobody inside (the most recently entered are kept). -1 every room, 0 none |
| `rooms` | `maxRoomSize` | 3 | the largest a room can grow, in chunks along one side (1 to 6) |
| `rooms` | `growItem` | `alchyrift:void_seed` | the item a growth costs |
| `rooms` | `growCosts` | `[1, 3, 6, 10, 15]` | how many of that item each growth costs, from 1 x 1 to 2 x 2, then 2 x 2 to 3 x 3, and so on. Later growths cost the last number |
| `rooms` | `growExtras` | `["", "minecraft:nether_star"]` | one extra item per growth, in the same order. `""` for none |
| `vision` | `maxViewDistance` | 64 | how far from a gate a player is sent its room's miniature |
| `conduits` | `maxSurges` | 3 | surge crystals per pulling end. 0 turns them off |
| `conduits` | `surgeFactor` | 4 | how much each surge crystal multiplies what moves |
| `gates` | `floorBlocks` | overworld grass, nether netherrack, end end stone, rift void void stone | the floor a gate takes for each dimension, as `dimension=block`. Add your own dimensions here. An empty list turns it off |
| `gates` | `openingPush` | true | whether the blast of an opening rift throws players back (it never hurts) |

On a server, the common config is sent to players so screens show the server's numbers.

## Client

`config/alchyrift-client.toml`, section `vision`:

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | true | show rooms above stabilizers at all |
| `viewDistance` | 48 | how far from a gate its room is drawn. The server may allow less |
| `maxFaces` | 60000 | most block faces drawn per room. Bigger rooms lose their lowest blocks first |

## Datapacks

The gates are AlchyX multiblocks (`data/alchyrift/multiblock/rift_gate.json` and `greater_rift_gate.json`), so a datapack can change their shape. See [Pattern Format](https://github.com/JustAlex14/AlchyX/wiki/Pattern-Format).
