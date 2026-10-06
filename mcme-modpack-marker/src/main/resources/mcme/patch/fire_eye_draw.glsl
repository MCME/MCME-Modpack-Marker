// MCME: the fire eye, drawn over a finished scene (patch_shaderpack.py)
#include "/lib/mcme/far_terrain.glsl"
#include "/lib/mcme/fire_eye_config.glsl"
#define FIRE_NO_GLOW
int fireLayer = 0;
vec3 fireCentre = vec3(0.0);
float fireTime = 0.0;
vec3 fireRay = vec3(0.0, 0.0, -1.0);
#define Pos fireRay
#include "/lib/mcme/fire_eye.glsl"
#undef Pos

vec3 mcmeFireColour(vec3 c) {
#if MCME_LINEAR
    return pow(c, vec3(2.2));
#else
    return c;
#endif
}

// How much of the eye shows through what lies between it and the camera that
// the scene doesn't record the distance of - clouds - where a recipe can tell
// (1: all of it). The scene already holds those clouds, so the eye only fades.
float mcmeFireVisibility = 1.0;

// The scene's colour with the eye and its glow over it. viewPos: a point the
// pixel shows, in view space (any point along its ray, for the sky);
// sceneDistance: how far off that is (huge for the sky); scale: from the
// resource pack's colours into the scene's, before its exposure.
vec3 mcmeDrawFireEye(vec3 color, vec3 viewPos, float sceneDistance, float scale) {
    // relative to where the camera truly is: the game puts its view bobbing
    // into the projection, moving it off the view's origin as the player
    // walks - the point the projection draws towards, which it maps to w = 0.
    // Without it the eye wiggles against the world
    vec4 mcmeEyePoint = gbufferProjectionInverse * vec4(0.0, 0.0, 1.0, 0.0);
    vec3 mcmeOrigin = abs(mcmeEyePoint.w) > 1.0e-6 ? mcmeEyePoint.xyz / mcmeEyePoint.w : vec3(0.0);
    vec3 origin = mat3(gbufferModelViewInverse) * mcmeOrigin + gbufferModelViewInverse[3].xyz;
    fireCentre = vec3(MCME_EYE_BLOCK - cameraPositionInt) + 0.5 - cameraPositionFract - origin;
    fireTime = frameTimeCounter;
    fireRay = normalize(mat3(gbufferModelViewInverse) * (viewPos - mcmeOrigin));
    float fireDistance = length(fireCentre);
#if defined DISTANT_HORIZONS || defined VOXY
    float range = 1.0;
#else
    // without a distant terrain mod nothing is drawn where the eye's block
    // can't be, so neither is the eye: it fades out over the last chunk of the
    // render distance - a sphere - and of the server's view distance across
    float range = (1.0 - smoothstep(far - 16.0, far, fireDistance))
                * (1.0 - smoothstep(FIRE_HANDOVER - 16.0, FIRE_HANDOVER, length(fireCentre.xz)));
    if (range <= 0.0) return color;
#endif
    // the eye, where the ray passes near enough to meet it, hidden by
    // whatever is nearer than its front
    vec3 from = -fireCentre / FIRE_RADIUS;
    float pass = length(from + fireRay * max(dot(-from, fireRay), 0.0));
    if (pass < max(FIRE_EYE_WIDTH, FIRE_CORONA) * 1.05) {
        vec4 eye = fireColor();
        float shown = smoothstep(fireDistance - FIRE_RADIUS * 1.2, fireDistance - FIRE_RADIUS * 0.7, sceneDistance);
        color = mix(color, mcmeFireColour(eye.rgb) * scale * MCME_BRIGHTNESS, eye.a * shown * mcmeFireVisibility * range);
    }
    // its glow, as light over what is behind it: none in front of the ball,
    // all of it FIRE_GLOW_DEPTH radii behind
    float glowShown = smoothstep(fireDistance - FIRE_RADIUS, fireDistance + FIRE_RADIUS * FIRE_GLOW_DEPTH, sceneDistance);
    return color + mcmeFireColour(fireGlowLight(fireRay, fireCentre)) * scale * MCME_BRIGHTNESS * MCME_GLOW
                 * glowShown * mcmeFireVisibility * range;
}
