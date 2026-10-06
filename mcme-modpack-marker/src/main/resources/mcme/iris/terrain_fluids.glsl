// MCME: the resource packs' water, and RP-Mordor's lava and tar, in a shader
// pack's terrain (MCME's mod: IrisTerrain). Every lookup of the block
// atlas the pack makes is wrapped: where it lands on a fluid's texture - told
// by the code in its texels (fluid.glsl) - it gets the fluid's colour drawn
// there instead, which the pack then lights and shades as it would the
// texture. Close up only: Distant Horizons' LODs keep the pack's own.
// Appended to Iris's finished shader, after everything of the pack's, so its
// defines touch nothing of it; everything of the resource packs' here is
// renamed mcme_..., so none of its names can clash with the pack's.

// The fragment, from the camera, and its change to the next pixel across and
// up: from its depth, taken at the top of main(), before anything branches.
// (Iris's depth is reversed: 1 - z is the pack's.)
vec3 mcmeRel = vec3(0.0);
vec3 mcmeRelDx = vec3(0.0);
vec3 mcmeRelDy = vec3(0.0);

void mcmeFluidFrame() {
    vec4 ndc = vec4(gl_FragCoord.xy / vec2(viewWidth, viewHeight), 1.0 - gl_FragCoord.z, 1.0) * 2.0 - 1.0;
    vec4 view = gbufferProjectionInverse * ndc;
    mcmeRel = mat3(gbufferModelViewInverse) * (view.xyz / view.w) + gbufferModelViewInverse[3].xyz;
    mcmeRelDx = dFdx(mcmeRel);
    mcmeRelDy = dFdy(mcmeRel);
}

// No shores: a shader pack's terrain isn't told them (water.glsl).
WaterShore mcmeNoShore() {
    WaterShore s;
    s.at = vec2(0.0);
    s.dx = vec2(0.0);
    s.dy = vec2(0.0);
    s.shore = vec4(0.0);
    s.open = 1.0;
    return s;
}

// the last lookup's answer: packs look up the same texel more than once
bool mcmeCached = false;
vec2 mcmeCachedUv = vec2(0.0);
vec4 mcmeCachedIn = vec4(0.0);
vec4 mcmeCachedOut = vec4(0.0);

// What the pack gets for sampled, its lookup of atlas at uv.
vec4 mcmeFluid(sampler2D atlas, vec2 uv, vec4 sampled) {
    if (mcmeCached && uv == mcmeCachedUv && sampled == mcmeCachedIn) return mcmeCachedOut;
    vec2 dv = vec2(dFdx(uv.y), dFdy(uv.y));
    int kind = fluidKind(atlas, uv);
    vec4 result = sampled;
    if (kind >= 0) {
        FluidFrame f = FluidFrame(mod(mcmeRel + vec3(cameraPositionInt & 63) + cameraPositionFract, 64.0),
                                  mcmeRel, mcmeRelDx, mcmeRelDy, dv);
        // (its patterns are whole again every 1200 seconds)
        float time = mod(frameTimeCounter, 1200.0);
        if (kind == WATER_STILL || kind == WATER_FLOWING) {
            // the pack tints it with the biome's colour, as the texture
            WaterLook water = waterLook(kind, f, time, mcmeNoShore());
            result = vec4(mix(vec3(water.shade), WATER_FOAM_COLOR, water.foam), water.alpha);
        }
#ifdef MCME_LAVA
        if (kind == LAVA_STILL || kind == LAVA_FLOWING) result = vec4(lavaColor(kind, f, time), 1.0);
#endif
#ifdef MCME_TAR
        if (kind == TAR) result = vec4(tarColor(f, time, mcmeNoShore()), 1.0);
#endif
    }
    mcmeCached = true;
    mcmeCachedUv = uv;
    mcmeCachedIn = sampled;
    mcmeCachedOut = result;
    return result;
}

vec4 mcmeTexture(sampler2D atlas, vec2 uv) {
    return mcmeFluid(atlas, uv, texture(atlas, uv));
}

vec4 mcmeTexture(sampler2D atlas, vec2 uv, float bias) {
    return mcmeFluid(atlas, uv, texture(atlas, uv, bias));
}

vec4 mcmeTextureLod(sampler2D atlas, vec2 uv, float lod) {
    return mcmeFluid(atlas, uv, textureLod(atlas, uv, lod));
}

vec4 mcmeTextureGrad(sampler2D atlas, vec2 uv, vec2 dx, vec2 dy) {
    return mcmeFluid(atlas, uv, textureGrad(atlas, uv, dx, dy));
}

void main() {
    mcmeFluidFrame();
    mcmePackMain();
}
