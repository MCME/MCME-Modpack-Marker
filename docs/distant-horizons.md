# Distant Horizons

Four things, each with its own setting.

## Resource-pack shaders (`dhResourcePackShaders`)

DH has two renderers. Its Blaze3D one reads shaders through the game's
resources, so resource packs can replace them. Its OpenGL one, which DH
switches to whenever Iris is installed, reads them from its own jar only.
`DhShaders` (hooked into `GlShader.loadFile`) makes the OpenGL renderer take a
resource pack's file at the same path first, with `#moj_import` lines filled
in from the packs' `shaders/include/` as the game does.

DH's OpenGL terrain program isn't told the camera position or the time, so
`GlDhTerrainShaderProgramMixin` sets `uMcmeCameraBlock` (ivec3),
`uMcmeCameraFrac` (vec3) and `uMcmeTime` (float, seconds into the day) on any
DH shader that declares them.

RP-Mordor's two terrain overrides (Blaze3D and OpenGL) differ only in DH's
own declarations: their MCME part - the eye, lava and water - lives once, in
`minecraft:shaders/include/mordor_dh_terrain.glsl`, and the clouds' in
`mordor_dh_clouds.glsl`. Change those, not the overrides.

RP-Mordor overrides DH's terrain, generic (clouds) and fade shaders, both
renderers' versions (`assets/distanthorizons/shaders/*/blaze/` and `*/gl/`).
An override replaces DH's file completely. When DH changes its own shader, the
override must follow: ResourcePackScripts' `checkShaders.py` compares each
override's inputs, outputs and uniforms with DH's and fails on a difference.

## Biome blending (`dhBiomeBlendingOff`)

With blending on, DH tints a LOD's water by the biomes around it, and if it
can't resolve one of them it tints none. Rivers, which always have banks
nearby, then show the water texture's untinted grey. `DhDefaults` sets
blending to 0 through DH's API: an override while the mod runs, never written
to the player's DH config. It retries each tick until DH's API is ready.

DH bakes colours into the LODs it builds, so changing the setting rebuilds
them (`DhReload.afterColorSetting`), as does "True colours far off".

## LOD colours and opacity (`dhExactColors`)

DH gives each block state one colour, worked out once from its model
(`ClientBlockStateColorCache`), plus a texture per face for textured LODs
(`ClientBlockStateTextureCache`). It gets the model's faces through
`QuadWrapper.getQuadsForDirection`. Four things in MCME's packs fooled it:

1. **Models drawn through Fabric's renderer have no faces for DH.** Special
   Model Loader's `.obj` models (all of MCME's custom leaves and many other
   blocks) return no quads from `BlockStateModelPart.getQuads`; they draw
   through Fabric's `emitQuads`. DH then fell back to the particle texture.
   For a multipart blockstate that's the *first entry's* texture, whatever the
   state (RP-Human's jungle door: red sandstone, so its leaf slabs showed tan).
   `ModelQuads` collects what such a model emits, turns each quad into a
   `BakedQuad` (with Fabric's atlas sprite finder), and `DhQuadWrapperMixin`
   hands those to DH when its own list is empty.
2. **Slabs and huge mushroom blocks were coloured as another state.** DH
   colours a slab as its double slab, and a mushroom block as its default
   state, which looks the same in vanilla. MCME gives those states models of
   their own. `DhBlockColorMixin` keeps DH's choice only when the real state's
   textures all appear in the substitute's (`ModelQuads.looksLike`).
3. **Doors and trapdoors were left out of LODs.** DH gives a block that
   doesn't occlude and passes skylight an opacity of 0, and doesn't draw it.
   MCME's leaf slabs sit on door and trapdoor states, so trees lost their
   outer leaves far off. `DhBlockOpacityMixin` makes a state whose model is
   mostly leaves (by area, textures named `*leaves*`) as opaque as a leaf block.
4. **Blocks no one can see were drawn black.** MCME's shadow block is tinted
   glass with a blank texture: it blocks skylight to darken canopies. DH
   colours a block by its texture's pixels, so it drew it black. A low
   opacity doesn't keep a block out of a LOD (DH only tints by such blocks);
   DH's ignored-blocks list does, which it treats as air.
   `DhIgnoredBlocksMixin` adds every state whose faces are all fully
   see-through (`LodColors.unseen`) to that list as DH makes it, and
   `DhReload` has DH make it again after a pack switch. The shade the block
   casts stays, in the light DH keeps. The log says how many states it added.

Also in `DhBlockColorMixin`: water's opacity. DH takes it from the water
texture's (two thirds to three quarters), so a waterfall one block thick
showed the dark cliff behind it as holes. MCME's water seen from afar is all
but opaque close up (its murk), so far off it's 96% (`WATER_OPACITY`).

And a model with no full-cube faces is coloured by
the average of all its faces, each over the part of its texture it uses,
weighted by size (`LodColors.of`), instead of one face's whole texture.

DH caches colours for the session and LOD data on disk. Changing the setting,
or the resource packs, rebuilds them.

### Debugging LOD colours

Look at the block and run `/mcme dh`. It prints, and logs:

- the state, and vanilla's occlusion and skylight flags, which decide DH's opacity;
- DH's stored values for it (opacity, solid, material id);
- its particle texture;
- how many faces its model has of its own, and the faces DH gets per side, with textures;
- the colour `LodColors` gives it.

No faces at all means DH colours it by its particle texture. Opacity 0 means
DH leaves it out.

## Switching resource packs

DH builds its shaders once, and keeps what it learnt of each block - colour,
face textures, opacity - for good, so on its own it keeps the old packs' look
until a restart. MCME's server switches packs between its worlds. After every
resource reload, once DH has been drawing, `DhReload`:

1. drops DH's OpenGL shader programs - the terrain's (rebuilt when next asked
   for), each post-processing pass's (rebuilt by its next `render()`), the
   generic objects' (rebuilt at once) - so they're compiled again from the
   packs as they now are;
2. forgets what DH knows of blocks: each level's colour cache, the tint and
   face-texture caches, the texture tile registry; and works out each block's
   opacity again;
3. has DH rebuild its LODs (`DhApi.Delayed.renderProxy.clearRenderDataCache()`).

The LODs redraw over a few seconds. If any of DH's internals it reaches has
changed, it logs why and a toast asks the player to restart instead.

## After a DH update

1. Run `/mcme hooks` in game: it lists any hook into DH the update broke. Fix those in their mixin, `DhReload`, and `Hooks`.
2. Run ResourcePackScripts' `checkShaders.py --fetch latest` on RP-Mordor: it diffs the shader overrides against the new DH.
3. In game, check the far eye, LOD lava, river colour, `/mcme dh` on a custom leaf block, and a resource pack switch.
