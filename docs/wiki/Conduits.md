# Conduits

Conduits carry things between blocks that hold them: chests, furnaces, tanks, machines.

| Conduit | Carries | Made with |
|---|---|---|
| Item Conduit | items | obsidian (eight at a time) |
| Fluid Conduit | fluids | obsidian and lapis |
| Energy Conduit | Forge Energy | obsidian and gold |

The three kinds never join each other: a line carries one sort of thing.

## The Rift Tuner

Every end of a line, where it touches a block, starts out pushing. Use the tuner on an end to cycle it:

1. **pull**: takes from the block
2. **sealed**: does nothing
3. **push** again: gives to the block

Nothing moves until an end pulls.

## How much moves

A pulling end takes, every half second:

- 16 items
- a bucket (1,000 mB) of fluid
- 4,000 energy

and hands it to each pushing end in turn. From a furnace it takes only what is finished. Into a furnace it puts fuel and ore each where they belong.

## Sieves

A **Rift Sieve** on an end chooses what passes. Name the things from what you carry: only these, or all but these. A bare hand on that end opens it again. A sieve that names nothing lets everything through. Breaking the conduit gives the sieve back.

- On a fluid conduit, show the sieve the fluid's bucket.
- The **Greater Rift Sieve** also tells a thing from its twin: enchanted from plain, named from nameless.

## Surge Crystals

A **Surge Crystal** on a pulling end makes it move four times as much. Up to three fit on one end. Sneak with the tuner to take them back.

## Seeing the lines

With the tuner in hand, links between relays show as drifting motes. See [Relays and Ports](Relays-and-Ports).
