# Architecture

Source: `mcme-modpack-marker/src/main/java/im/opl/mcme/marker/`.

| Package / class | What it does |
|---|---|
| `MCMEModpackMarker` | Entry point: the `hello` payload, DH defaults, the updater, the hook check, the `/mcme` commands. |
| `Hooks` | Checks at startup that every Iris and DH class and member the mod hooks still exists; `/mcme hooks`. |
| `McmeConfig`, `SettingsScreen`, `ModMenuIntegration` | Settings: a Gson file, the screen (`/mcme`, Mod Menu). |
| `ShaderClock` | Real-time day clock for vanilla's `GameTime` uniform and DH's `uMcmeTime`. |
| `iris/` | Edits Iris's compiled terrain programs for any pack (`IrisTerrain`), draws the eye over the final frame (`EyeOverlay`), reads Iris internals by reflection (`IrisAccess`). |
| `shaderpacks/` | The recipes, one class each (`BlissRecipe`, `ComplementaryRecipe`, `BslRecipe`, `MakeUpRecipe`, `MellowRecipe`, `SolasRecipe`, `SildursRecipe`), their shared editing helpers and the patcher itself (`ShaderPackPatcher`), and the cache of edited packs Iris loads instead (`PatchedShaderPacks`). |
| `dh/` | DH shader overrides (`DhShaders`), DH settings (`DhDefaults`), LOD colours and opacity (`LodColors`, `ModelQuads`), bringing DH up to new resource packs without a restart (`DhReload`). |
| `update/` | The updater (`Updater`) and the process that swaps the jar after the game closes (`Swapper`). |
| `mixin/` | Every hook into Minecraft, Iris and DH (below). |

Shader sources the mod injects are resources: `resources/mcme/iris/` (generic
route) and `resources/mcme/patch/` (recipes). `tools/recipes/` checks the recipes
against the packs they're for ([shader-packs.md](shader-packs.md)).

## Hooks into other mods

The mod depends on nothing but Fabric at compile time. Iris and DH are reached
through `@Pseudo` mixins (targets named by string) and reflection, so the mod
builds and runs without them. `MixinPlugin` applies the mixins in `mixin/dh/`
only when DH is installed, and those in `mixin/iris/` only with Iris.

Every injector has `require = 0`. If a target was renamed in an update, the
hook silently does nothing, and so does the feature. Each handler also catches
its own errors, logs once, and turns itself off for the session.

So that this never goes unnoticed, `Hooks` checks once the game has started
that every class and member below - and those reached by reflection - still
exists, and logs a warning naming the missing ones and the features that are
off. `/mcme hooks` shows the same in game. **After updating Iris or DH, run
`/mcme hooks`**; when a hook moves, update both its mixin and its `Hooks`
entry.

| Mixin | Target (mod, class, member) | Feature |
|---|---|---|
| `GlobalSettingsUniformMixin` | Minecraft `GlobalSettingsUniform.update` → 2nd `Std140Builder.putFloat` | GameTime from `ShaderClock` |
| `SpriteContentsAccessor` | Minecraft `SpriteContents.originalImage` | Texture pixels for `LodColors` |
| `iris.IrisMixin` | Iris `Iris.loadShaderpack` HEAD, `loadExternalShaderpack` (`ShaderPack` constructor's path) | Hand Iris the edited copy of a pack |
| `iris.ShaderCreatorMixin` | Iris `ShaderCreator.create`, `createShadow` (first local stored) | Generic terrain edits (`IrisTerrain`) |
| `iris.IrisLodRenderProgramMixin` | Iris `IrisLodRenderProgram.createProgram` (first local stored) | DH programs under shader packs |
| `iris.IrisRenderingPipelineMixin` | Iris `IrisRenderingPipeline.finalizeLevelRendering` TAIL | Eye over the frame (`EyeOverlay`) |
| `dh.GlShaderMixin` | DH `GlShader.loadFile` | Resource-pack DH shaders (`DhShaders`) |
| `dh.GlDhTerrainShaderProgramMixin` | DH `GlDhTerrainShaderProgram.fillUniformData` TAIL | Camera and time uniforms |
| `dh.DhQuadWrapperMixin` | DH `QuadWrapper.getQuadsForDirection` RETURN | Faces of Fabric-rendered models (`ModelQuads`) |
| `dh.DhBlockColorMixin` | DH `ClientBlockStateColorCache.getQuadsForDirection` (arg 0), `resolveColors` (before `isColorResolved` is set) | Slab and mushroom states by their own model, custom models by all faces |
| `dh.DhBlockOpacityMixin` | DH `BlockStateWrapper.calculateOpacity` RETURN | Leaf slabs on doors and trapdoors kept in LODs |
| `dh.DhRendererTrackingMixin` | DH `GlAbstractShaderRenderer.<init>` RETURN | Post-processing passes rebuilt after a pack switch (`DhReload`) |
| `dh.DhGenericRendererTrackingMixin` | DH `GlGenericObjectRenderer.<init>` RETURN | Clouds' shaders rebuilt after a pack switch (`DhReload`) |

DH settings (`DhDefaults`) go through DH's public API, by reflection. `DhReload`
reaches DH's renderers and caches by reflection (listed in `Hooks`).

Last checked against: Minecraft 26.2, Fabric API 0.161.0, Sodium 0.9.2,
Iris 1.11.4, Distant Horizons 3.3.3.

## Threads

DH calls the colour and opacity hooks from its worker threads. `ModelQuads`
and `LodColors` read baked models and texture pixels only, which don't change
between resource reloads, and lock around model calls. `ModelQuads` caches
faces per block state together with the model they came from, so a resource
reload, which bakes new models, refreshes them.

## Files the mod writes

| Path | What |
|---|---|
| `config/mcme-modpack-marker.json` | Settings |
| `.minecraft/mcme/shaderpacks/` | Edited copies of shader packs, re-made when the pack, the resource packs' shaders or the mod change |
| `.minecraft/mcme/iris/` | Programs whose edit failed (all edited programs with `debugShaderDumps`) |
| `.minecraft/mcme/update/` | Downloaded updates, `swapper.jar`, `update.log` |
