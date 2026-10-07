# MCME Modpack Marker

The client mod in MCME's modpack. Its first job gave it its name: it tells the
server which modpack (and Sodium version) a player runs, on the
`mcme-modpack-marker:hello` channel. It has since become the place where
MCME's resource-pack effects are made to work with the mods players add:

- **Shader packs (Iris).** Under a shader pack the resource packs' terrain
  shaders don't run, so RP-Mordor's fire eye, lava, water and tar would be
  lost. The mod edits the shader pack as Iris loads it: a *recipe* for each
  pack MCME supports, and a generic route for any other.
  See [docs/shader-packs.md](docs/shader-packs.md).
- **Distant Horizons.** DH draws far terrain (LODs) with shaders of its own.
  The mod lets resource packs replace them (the fire eye and lava far off),
  turns off DH's biome blending (grey rivers), corrects how DH colours
  MCME's custom blocks, and brings DH up to a new resource pack without a
  restart. See [docs/distant-horizons.md](docs/distant-horizons.md).
- **A real-time clock** for the shaders' animations, which otherwise skip
  whenever the client stalls (`ShaderClock`).
- **Settings and updates.** An in-game settings screen, and an updater that
  installs new releases by itself. See [docs/releasing.md](docs/releasing.md).

Every feature degrades to "do nothing" when what it hooks isn't there or has
changed: no Iris, no DH, a DH update that renamed something. The game then
behaves as without the mod. How that works, and where each hook is, is in
[docs/architecture.md](docs/architecture.md).

## For players

- **Install:** drop `mcme-modpack-marker-<version>-mc<Minecraft>.jar` into the
  mods folder. Needs Fabric Loader and Fabric API. Iris, Sodium, Distant
  Horizons and Mod Menu are optional.
- **Settings:** Mod Menu → MCME Modpack Marker, or `/mcme`. Stored in
  `config/mcme-modpack-marker.json`. Everything is on by default.
- **Updates:** automatic. A new version downloads in the background and
  replaces the old jar when the game closes.

| Setting | What it does |
|---|---|
| Update automatically | Download and install new releases. |
| Shader pack recipes | Edit supported shader packs (Bliss, Complementary, BSL, MakeUp, Mellow, Solas, Sildur's) to draw the fire eye and lava. |
| Fluids in shader packs | Show the resource packs' water, lava and tar in any shader pack. |
| Fire eye over shader packs | Draw the eye over packs without a recipe. |
| Distant Horizons shaders | Let resource packs replace DH's shaders (the eye and lava far off). |
| Smooth animation clock | Animate water, lava and the eye by real time. |
| Fix grey rivers far off | Turn DH's biome blending off. |
| True colours far off | Colour DH's LODs of MCME's custom blocks as they look. |

`debugShaderDumps` (config file only) writes every shader-pack program the mod
edits to `.minecraft/mcme/iris/`. Failed edits are always written there.

## For developers

```
cd mcme-modpack-marker
./gradlew build          # needs JDK 25
```

The jar lands in `build/libs/mcme-modpack-marker-<version>-mc<Minecraft>.jar`.
Test it in a client with the mods it hooks: Fabric API, Sodium, Iris,
Distant Horizons. Close the game before replacing the jar: replacing it
under a running game breaks the mod's resource reads until a restart.

**`/mcme hooks`** lists any hook into Iris or Distant Horizons that an update
of theirs broke, and the features that turned off with it. The same is logged
at every start.

**`/mcme dh`** prints what Distant Horizons makes of the block you look at:
its opacity, the faces and textures it gets, and the colour the mod gives it.
It also writes this to the log. Start there whenever LOD colours look wrong.

`mcme-marker-test-plugin/` is a Paper plugin that logs the `hello` payload,
for testing the marker against a local server.

| Doc | For |
|---|---|
| [architecture.md](docs/architecture.md) | How the mod is put together, and every hook into another mod |
| [shader-packs.md](docs/shader-packs.md) | Recipes, the generic route, testing a shader pack edit |
| [distant-horizons.md](docs/distant-horizons.md) | DH shaders, settings and LOD colours, and what to check after a DH update |
| [releasing.md](docs/releasing.md) | Versions, the release workflow, the updater |

The resource-pack side (the shaders these hooks run, the packs' build and
checks) lives in ResourcePackScripts: `docs/shader-base.md` there.
