# Shader packs (Iris)

Under an Iris shader pack, the resource packs' terrain shaders don't run. The
mod brings MCME's effects back two ways.

## Recipes (`shaderPackRecipes`)

A recipe for each supported pack, each its own class: `BlissRecipe`,
`ComplementaryRecipe` (Complementary Reimagined, Unbound, Spooklementary),
`BslRecipe`, `MakeUpRecipe`, `MellowRecipe`, `SolasRecipe`, `SildursRecipe`.
`ShaderPackPatcher` holds what they share - finding and editing code in a
pack, the eye's and the lava's includes and uniforms - and tries them in turn
(`RECIPES`). A recipe draws RP-Mordor's fire eye inside the pack's own passes,
after fog and clouds and before bloom. Where it knows the pack's terrain
programs, it also draws lava there and in the DH and Voxy programs
(`LavaWhere`).

- **Packs are recognised by their code, not their name.** A recipe checks
  everything it needs before changing anything. If the pack isn't its own, or
  its code has changed, it refuses (`Unsupported`) and the next recipe is
  tried. A pack no recipe takes gets the generic route.
- **The edit goes into a copy.** `PatchedShaderPacks` writes the edited pack
  to `.minecraft/mcme/shaderpacks/` and hands Iris that folder instead (via
  `IrisMixin`). The player's pack keeps its name and settings. The copy is
  rebuilt when the pack, the resource packs' shaders or the mod change.
- **The effects come from the resource packs.** The eye and lava shaders
  (`fire_eye*.glsl`, `far_terrain.glsl`, `fluid.glsl`, `lava*.glsl`) are read
  from the loaded resource packs' `minecraft:shaders/include/` and copied
  into the pack's `lib/mcme/`. Without RP-Mordor there is nothing to draw,
  and the pack is left alone.
- **Packs already edited** (with an `MCME-PATCH.txt` next to `shaders/`, from
  the old installer) load as they are.

**The Lite zip:** when the resource packs' `mcme_lite.glsl` defines
`MCME_LITE` (ResourcePackScripts builds the Lite zip so), recipes draw the eye
only, and the generic route adds no fluids either.

### Checking the recipes

`tools/recipes/check_recipes.py` checks every recipe against its pack, at the
versions in `tools/recipes/packs.json`: it downloads each (Modrinth, or
GitHub for Bliss), patches it with the built jar, requires the right recipe
to take it, and compiles every program the patch changed with glslang -
before and after, the way Iris hands them to the driver - failing on any
error the patch brought in.

```
python tools/recipes/check_recipes.py --jar <built jar> \
  --include <RP-Mordor>/assets/minecraft/shaders/include \
  --include <ResourcePackScripts>/shaderBase/assets/minecraft/shaders/include
```

GitHub runs it (`.github/workflows/recipes.yml`) on every change to the
recipes and weekly, against RP-Mordor's default branch. To support a new
version of a pack, change it in `packs.json` and run the check.

### Testing a recipe without the game

`ShaderPackPatcher` has a `main` for this:

```
java -cp build/libs/mcme-modpack-marker-<v>-mc<mc>.jar \
  im.opl.mcme.marker.shaderpacks.ShaderPackPatcher <unzipped pack> \
  <RP-Mordor-common>/assets/minecraft/shaders/include \
  <ResourcePackScripts>/shaderBase/assets/minecraft/shaders/include
```

It patches the unzipped pack in place and prints the recipe that took it, or
each recipe's refusal. Then compile the changed programs with glslang, and
compare them with the unpatched pack, to see only what the edit broke. Iris
rewrites `gl_FragData` into `layout(location = n) out`, so a recipe that adds
outputs must not collide with the pack's own (error C7599).

### When a supported pack updates

Its recipe will most likely refuse the new version, and the pack falls back to
the generic route; nothing breaks. To support the new version, patch it with
the CLI above, read the refusals, and adapt the recipe's anchors (the code
lines it searches for).

## Generic route (`shaderPackTerrain`, `shaderPackEye`)

For any pack, `IrisTerrain` edits the GLSL Iris has finished generating for
Sodium's terrain programs (`sodium_terrain_*`), just before compiling:

- the eye block's faces are dropped, in the shadow pass too, and
  `EyeOverlay` draws the eye over the finished frame instead, hidden by the
  game's and DH's depth;
- every block-atlas lookup is wrapped, so that where it lands on the water's,
  lava's or tar's signed texture, the pack gets the colour as the resource
  packs draw it (`resources/mcme/iris/terrain_fluids.glsl`).

Each edited program is linked once to test it. If that fails, Iris gets the
original and the error is written to `.minecraft/mcme/iris/`.

Generic water and tar also run on top of the recipes. The generic route
flickers under TAA and has no LOD lava, which is why the supported packs get
recipes.
