# Beacon

A minecraft mod to scan the world for items or blocks. Under the hood, this uses [modified version](https://github.com/Crec0/mc-scanner) of [mc-scanner by Skyrising](https://github.com/SciCraft/mc-scanner).

Main purpose of this mod is to help with world eaters or quarries and scan the perimeter area to find all the problem blocks like obsidian, trial vault, trial spawner, block entities, waterlogged blocks, etc

By default it includes the following subcommands as a QOL for such searches:

- **anti-world-eater**:
  - explosion resistance > 10
  - destroy time != -1 (cannot be mined)
  - not water, not lava
  - is waterlogged
  - cannot be destroyed by piston action
  - [block list can be found here](https://joakimthorsen.github.io/MCPropertyEncyclopedia/?selection=blast_resistance,waterloggable,movable,hardness&filter=(blast_resistance:0,0.1,0.2,0.25,0.3,0.4,0.5,0.6,0.65,0.7,0.75,0.8,1,1.4,1.5,1.8,2,2.5,2.8,3,3.5,4,4.2,4.8,5,6,9,10);(movable:Breaks);(hardness:%E2%88%9E)#)
- **anti-quarry**
  - destroy time != -1 (cannot be mined)
  - not water, not lava
  - cannot be pushed with piston
  - [block list can be found here](https://joakimthorsen.github.io/MCPropertyEncyclopedia/?selection=movable,hardness&filter=(movable:Breaks,Yes);(hardness:%E2%88%9E)#)

### Command

#### Implemented
- `beacon anti-world-eater <x0> <y0> <z0> <x1> <y1> <z1> <label y> <print waypoints>`
- `beacon anti-quarry <x0> <y0> <z0> <x1> <y1> <z1> <label y> <print waypoints>`
- `beacon block <blockstate> <x0> <y0> <z0> <x1> <y1> <z1> <label y> <print waypoints>`
- `beacon clear`

#### TODO
-[ ] Add item subcommand: `beacon item <item> <x0> <y0> <z0> <x1> <y1> <z1> <label y> <print waypoints>`
-[ ] Re-add rendering
-[ ] Add some sort of decay mechanic for rendering
-[ ] Add proper logging
