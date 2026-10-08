// MCME: RP-Mordor's lava (lava.glsl) in a shader pack's terrain, put in by
// MCME's mod (ShaderPackPatcher). The shader pack lights it as it lights
// lava - it only takes the lava's colour from here. Needs the pack to
// declare cameraPositionInt and cameraPositionFract, which Iris gives.
#include "/lib/mcme/fluid.glsl"
#include "/lib/mcme/lava_config.glsl"
#include "/lib/mcme/lava.glsl"

// The time the lava moves by: seconds, starting over every 1200 - its
// patterns are whole again then (lava.glsl). frameTimeCounter starts over
// every 3600, three times that.
float mcmeLavaTime(float frameTimeCounter) {
    return mod(frameTimeCounter, 1200.0);
}

// Where on its face a fragment is (fluid.glsl's FluidFrame), from rel, its
// position relative to the camera, and uv, the atlas coordinate it shows.
// It takes derivatives: call it where they're defined - in main(), not under
// anything that branches.
FluidFrame mcmeLavaFrame(vec3 rel, vec2 uv) {
    vec3 world = rel + vec3(cameraPositionInt & 63) + cameraPositionFract;
    return fluidFrame(mod(world, 64.0), rel, uv);
}

#ifdef MCME_SCREEN_REL
// The fragment's position relative to the camera, from where it is on the
// screen and its depth, by projectionInverse, the projection's it was drawn
// with inverted: for a program that has none at the top of main().
vec3 mcmeScreenRel(mat4 projectionInverse) {
    vec4 view = projectionInverse * vec4(vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z) * 2.0 - 1.0, 1.0);
    return mat3(gbufferModelViewInverse) * (view.xyz / view.w) + gbufferModelViewInverse[3].xyz;
}
#endif

// The lava's colour where the terrain shows atlas texel uv of atlas, with
// here from mcmeLavaFrame(); or albedo, if that isn't the resource pack's
// lava (its textures carry a code, fluid.glsl).
vec3 mcmeLava(sampler2D atlas, vec2 uv, FluidFrame here, float time, vec3 albedo) {
    int kind = fluidKind(atlas, uv);
    return kind == LAVA_STILL || kind == LAVA_FLOWING ? lavaColor(kind, here, time) : albedo;
}

// The same, where derivatives can't be had (Voxy's fragments): from normal,
// the face's, in the world's axes, and pixel, how big a screen pixel is
// there, in blocks.
FluidFrame mcmeLavaFrameFlat(vec3 rel, vec3 normal, float pixel) {
    vec3 world = rel + vec3(cameraPositionInt & 63) + cameraPositionFract;
    vec3 along = normalize(cross(normal, abs(normal.y) > 0.5 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)));
    vec3 dx = along * pixel;
    vec3 dy = cross(normal, along) * pixel;
    return FluidFrame(mod(world, 64.0), rel, dx, dy, vec2(0.0));
}
